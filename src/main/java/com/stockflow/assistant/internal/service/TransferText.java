package com.stockflow.assistant.internal.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A bank transfer's content read the way banks hand it back (docs/bank-feed-sepay.md): accents and {@code đ} gone,
 * upper case, every run of other characters a single space - and the same reading of the codes, names and tax codes
 * looked for in it, so both sides are compared in one spelling. {@code "Công ty ABC ck HD-20261120-900001 đợt 2"}
 * reads {@code "CONG TY ABC CK HD 20261120 900001 DOT 2"}.
 *
 * <p>What it finds is what {@link TransferMatchScorer} scores: an order or contract number with its dashes kept,
 * spaced or dropped, a {@code DOTn}/{@code FEEn}, a customer's name without its legal form ("Công ty TNHH"), a tax code
 * as a number of its own. It never decides what the transfer pays: the scorer only ranks, the Accountant assigns.</p>
 */
final class TransferText {

    /**
     * Words of a company's legal form and trade, which every second customer's name holds: "CÔNG TY TNHH THƯƠNG MẠI
     * DỊCH VỤ ABC" is known in a transfer by "ABC". Longest first, so "MOT THANH VIEN" goes before "THANH".
     */
    private static final List<List<String>> LEGAL_FORM = phrases(
            "CONG TY TRACH NHIEM HUU HAN", "TRACH NHIEM HUU HAN", "MOT THANH VIEN", "XUAT NHAP KHAU",
            "HO KINH DOANH", "DOANH NGHIEP TU NHAN", "CONG TY CO PHAN", "CO PHAN", "CONG TY", "THUONG MAI", "DICH VU",
            "SAN XUAT", "DAU TU", "PHAT TRIEN", "TAP DOAN", "CHI NHANH", "CUA HANG", "DOANH NGHIEP", "CTY", "TNHH",
            "MTV", "CP", "TM", "DV", "SX", "XNK", "TMDV", "SXTM", "DNTN", "HKD", "JSC", "LTD", "COMPANY");

    /**
     * Words a transfer's content is made of whoever pays: a one-word name that is one of them says nothing about who
     * paid ("THANH TOAN DON HANG" is no customer called "HANG").
     */
    private static final Set<String> TRANSFER_WORDS = Set.of("CK", "CT", "TT", "DEN", "TU", "THANH", "TOAN", "CHUYEN",
            "KHOAN", "TIEN", "HANG", "DON", "HD", "SO", "DOT", "FEE", "NOP", "TRA", "MUA", "BAN", "LY", "COC", "IN",
            "GIAY", "NHUA", "HOP", "DONG", "THUE", "PHI", "IBFT", "MBVCB", "FT", "REF", "TRACE", "NAPAS", "VND");

    /** A one-word name shorter than this is a word, not a name. */
    private static final int SHORTEST_ONE_WORD_NAME = 3;
    /** A name compared with its spaces dropped must be at least this long, or it is found inside other words. */
    private static final int SHORTEST_GLUED_NAME = 6;

    private static final Pattern NOT_ALNUM = Pattern.compile("[^A-Z0-9]+");
    /** The last word of a transfer reference: {@code DOT2}, {@code FEE1}. */
    private static final Pattern PLACE = Pattern.compile("(DOT|FEE)(\\d{1,2})");

    private final String spaced;
    private final String glued;
    private final List<String> words;

    private TransferText(String spaced) {
        this.spaced = spaced;
        this.glued = spaced.replace(" ", "");
        this.words = spaced.isEmpty() ? List.of() : List.of(spaced.split(" "));
    }

    static TransferText of(String content) {
        return new TransferText(spaced(content));
    }

    /** The content as read: upper case, accents gone, separators a single space. */
    String spaced() {
        return spaced;
    }

    /**
     * Whether the content names order {@code orderNumber} ({@code SO-20261117-000123}): its day and sequence, dashes
     * kept, spaced or dropped, with or without {@code SO}. With {@code SO} before it the number may run on into digits
     * a bank glued after it ({@code SO20261117 8476387} names {@code SO-20261117-847638}); without it the digits must
     * stand alone, or any long number would contain some order's.
     */
    boolean namesOrder(String orderNumber) {
        String[] parts = parts(orderNumber);
        if (parts.length != 3 || !"SO".equals(parts[0])) {
            return false;
        }
        String number = parts[1] + " ?" + parts[2];
        return Pattern.compile("SO ?" + number).matcher(spaced).find()
                || Pattern.compile("(?<!\\d)" + number + "(?!\\d)").matcher(spaced).find();
    }

    /**
     * Whether the content names contract {@code contractNumber} ({@code HD-20261117-000123-1}) whole: {@code HD}, the
     * order's day and sequence and the contract's count, the separators kept, spaced or dropped, and no digit after
     * the count ({@code ...-1} is not {@code ...-12}).
     */
    boolean namesContract(String contractNumber) {
        String[] parts = parts(contractNumber);
        if (parts.length != 4 || !"HD".equals(parts[0])) {
            return false;
        }
        return Pattern.compile("(?<!\\d)HD ?" + parts[1] + " ?" + parts[2] + " ?" + parts[3] + "(?!\\d)")
                .matcher(spaced).find();
    }

    /**
     * Whether the content says which installment of a plan, as its transfer reference ends: {@code DOT2} or
     * {@code DOT 2} for the second, {@code FEE1} for the first fee item. Read only beside an order or contract number:
     * "đợt 2" alone names nobody's installment.
     *
     * @param transferReference the installment's own ({@code HD-20261117-000123-1 DOT2}); its last word is the place
     */
    boolean namesInstallment(String transferReference) {
        String[] parts = parts(transferReference);
        if (parts.length == 0) {
            return false;
        }
        java.util.regex.Matcher place = PLACE.matcher(parts[parts.length - 1]);
        if (!place.matches()) {
            return false;
        }
        return Pattern.compile("(?<![A-Z])" + place.group(1) + " ?0?" + Integer.parseInt(place.group(2)) + "(?!\\d)")
                .matcher(spaced).find();
    }

    /**
     * Whether the content holds the customer's name without its legal form ({@link #LEGAL_FORM}): its remaining words in
     * a row, or - for a name long enough not to hide inside other words - those words with the spaces dropped, as some
     * banks send them. A name that is only its legal form, or one short word, or one word every transfer holds, is never
     * found: it would point at whoever paid.
     */
    boolean namesCustomer(String name) {
        List<String> distinctive = distinctive(name);
        if (distinctive.isEmpty()) {
            return false;
        }
        if (distinctive.size() == 1) {
            String only = distinctive.getFirst();
            if (only.length() < SHORTEST_ONE_WORD_NAME || TRANSFER_WORDS.contains(only)) {
                return false;
            }
        }
        if (Collections.indexOfSubList(words, distinctive) >= 0) {
            return true;
        }
        String joined = String.join("", distinctive);
        return joined.length() >= SHORTEST_GLUED_NAME && glued.contains(joined);
    }

    /**
     * Whether the content holds tax code {@code taxCode} ({@code 0312345678}, or {@code 0312345678-001} for a branch) as a
     * number of its own: its ten digits not inside a longer number, the branch's three after them or not.
     */
    boolean namesTaxCode(String taxCode) {
        if (taxCode == null) {
            return false;
        }
        String digits = taxCode.replaceAll("\\D", "");
        if (digits.length() < 10) {
            return false;
        }
        String head = digits.substring(0, 10);
        return Pattern.compile("(?<!\\d)" + head + "(?: ?\\d{3})?(?!\\d)").matcher(spaced).find();
    }

    /** The words of {@code name} left once its legal form is taken out, read as the content is. */
    static List<String> distinctive(String name) {
        String read = spaced(name);
        if (read.isEmpty()) {
            return List.of();
        }
        List<String> words = new ArrayList<>(Arrays.asList(read.split(" ")));
        for (List<String> phrase : LEGAL_FORM) {
            int at;
            while ((at = Collections.indexOfSubList(words, phrase)) >= 0) {
                words.subList(at, at + phrase.size()).clear();
            }
        }
        return List.copyOf(words);
    }

    /** Accents and {@code đ} gone, upper case, every run of other characters one space, trimmed; null reads as empty. */
    static String spaced(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String plain = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        return NOT_ALNUM.matcher(plain).replaceAll(" ").strip();
    }

    /** A document number's parts as the content is read: {@code SO-20261117-000123} is SO, 20261117, 000123. */
    private static String[] parts(String number) {
        String read = spaced(number);
        return read.isEmpty() ? new String[0] : read.split(" ");
    }

    private static List<List<String>> phrases(String... phrases) {
        return Arrays.stream(phrases).map(phrase -> List.of(phrase.split(" ")))
                .sorted((a, b) -> Integer.compare(b.size(), a.size())).toList();
    }
}

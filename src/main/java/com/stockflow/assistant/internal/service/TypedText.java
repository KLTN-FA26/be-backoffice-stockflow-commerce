package com.stockflow.assistant.internal.service;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text people typed - a hold note, the reason of a status change, a customer's cancellation note - on its way to the
 * model provider (design §5.5): phone numbers, e-mail addresses and tax codes in it are replaced by a marker the model
 * can name ("[phone]") but not repeat. Best effort, for what a coordinator or a customer types into a note; the rest of
 * a note (a name, say) is the business's to accept or refuse sending (AQ-01).
 *
 * <p><b>Deliberately narrow.</b> The answer needs the codes and figures written in a note, so only what has the shape
 * of a contact is hidden. A phone number starts with 0 or +84, has 9 or 10 more digits and stands on its own: an order
 * number ({@code SO-20261028-000123}), a lot or SKU code after a dash, an amount ({@code 1250000000},
 * {@code 12.500.000}), a date or a quantity is left as typed. A bare ten-digit number is never taken for a tax code,
 * because amounts in VND are written that way; a tax code is hidden only after its label ({@code MST},
 * {@code mã số thuế}, with or without diacritics), in the shapes {@code TaxCode} accepts.</p>
 */
final class TypedText {

    static final String PHONE = "[phone]";
    static final String EMAIL = "[e-mail]";
    static final String TAX_CODE = "[tax code]";

    /**
     * Run on the text folded to plain letters ({@link #folded}), so the label matches however it was typed. A few words
     * may stand between the label and the number ("MST của khách: ...", "MST hộ kinh doanh ...").
     */
    private static final Pattern LABELLED_TAX_CODE = Pattern.compile(
            "\\b(?:mst|ma\\s*so\\s*thue)\\D{0,30}?"
                    + "(\\d(?:[ .]?\\d){9}(?:\\s?-\\s?\\d{3})?|\\d(?:[ .]?\\d){11})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)+");

    /**
     * 0, +84 or (+84), then 9 or 10 digits that may be grouped by spaces, dots or dashes; or a landline whose area code
     * is in parentheses ({@code (028) 3822 1234}). Not inside a word or a number, and not after a code's dash
     * ({@code LOT-0012345678}); and not followed directly by another digit, so an unbroken longer number (a bank
     * account) is not cut into a phone and a remainder. A parenthesis is part of the number only around an area code,
     * so a note's own "(0903 123 456, ...)" keeps its brackets.
     */
    private static final Pattern PHONE_NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(?<![\\p{L}\\p{N}_]-)"
                    + "(?:\\(0\\d{2,3}\\)[ .\\-]?\\d(?:[ .\\-]?\\d){6,7}"
                    + "|(?:(?:\\(\\+84\\)|\\+84)[ .\\-]?|0)\\d(?:[ .\\-]?\\d){8,9})"
                    + "(?!\\d)");

    /**
     * A Vietnamese mobile number however it is glued to its label ({@code SĐT0903123456}, {@code LH-0903 123 456}) or
     * written internationally without the plus ({@code 84903123456}, {@code 0084 903 123 456}, {@code +84 (0) 903...}):
     * 0, 84, +84 or 0084, then 3, 5, 7, 8 or 9 and eight more digits, spaced or dotted at will. Only a digit before it
     * stops it, so it never starts inside a longer number; a code such as {@code LOT-0012345678} starts with 00, which
     * no mobile number does.
     */
    private static final Pattern MOBILE_NUMBER = Pattern.compile(
            "(?<!\\d)(?:\\+?84|0084|0)(?:\\s*\\(0\\))?[ .\\-]*[35789](?:\\s*[.\\-]?\\s*\\d){8}(?!\\d)");

    private TypedText() {
    }

    /** {@code text} with every contact in it replaced by its marker; null stays null. */
    static String masked(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Tax codes first: a tax code starting with 0 has a phone number's shape. E-mails before phones: an address
        // may hold a phone number, and masking that first would leave the rest of the address behind.
        String masked = maskTaxCodes(Normalizer.normalize(text, Normalizer.Form.NFC));
        masked = EMAIL_ADDRESS.matcher(masked).replaceAll(Matcher.quoteReplacement(EMAIL));
        masked = MOBILE_NUMBER.matcher(masked).replaceAll(Matcher.quoteReplacement(PHONE));
        return PHONE_NUMBER.matcher(masked).replaceAll(Matcher.quoteReplacement(PHONE));
    }

    private static String maskTaxCodes(String text) {
        Matcher label = LABELLED_TAX_CODE.matcher(folded(text));
        StringBuilder masked = new StringBuilder(text.length());
        int kept = 0;
        while (label.find()) {
            // folded(text) has the length of text and its digits in the same places, so its offsets are text's.
            masked.append(text, kept, label.start(1)).append(TAX_CODE);
            kept = label.end(1);
        }
        return masked.append(text, kept, text.length()).toString();
    }

    /** Each character without its diacritics ("mã số thuế" becomes "ma so thue"), one for one. */
    private static String folded(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            folded.append(c < 0x80 ? c : Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).charAt(0));
        }
        return folded.toString();
    }
}

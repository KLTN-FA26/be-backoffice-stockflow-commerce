package com.stockflow.assistant.internal.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names a draft's text must not carry to the model provider (ADR-0015), and how they come back: the customer as
 * {@code KH#1}, each of its contact people as {@code LH#1}, {@code LH#2}, ... ({@link DraftWriter#names}).
 *
 * <p><b>More than the registered name, written whole.</b> People do not write a customer's registered name in a chat:
 * they write "bên Minh Phát" for "Công ty TNHH Bao Bì Minh Phát", and greet the person they deal with by name. So a
 * customer is found by each of its names (the registered one, the company name of its legal profile) and by the
 * distinctive part of each ({@link TransferText#distinctive}: the name without its legal form, as a bank transfer is
 * read), and its contact people by their full names. A name is found however it is accented or cased ("minh phat" is
 * "Minh Phát"), except a one-word distinctive part, which must be written with a capital: "Xanh" stands for a customer
 * called "Công ty Xanh", "cốc màu xanh" for a colour. A given name alone ("anh An") is not found: telling it from other
 * words is a guess this class does not make.</p>
 *
 * <p><b>Typed text cannot pose as an alias.</b> Text already shaped like {@code KH#3} or {@code LH#3} gets a space after
 * its {@code #} before anything is hidden ({@link TypedText} does the same for {@code KH#}), so only the aliases given
 * here are put back.</p>
 */
final class DraftNames {

    /** Hides nothing and reveals nothing: a draft about no customer (the producer's message). */
    static final DraftNames NONE = new DraftNames(null, List.of(), Map.of());

    /** A contact person's alias as written: {@code LH#2}, not part of a longer token. */
    static final Pattern CONTACT_ALIAS = Pattern.compile("(?<![\\p{L}\\p{N}#])LH#([1-9]\\d{0,2})(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALIAS_SHAPED = Pattern.compile("(?i)((?:KH|LH)#)(?=\\d)");

    /** A full name shorter than this is a word, not a name ({@link Pseudonyms} keeps the same floor). */
    private static final int SHORTEST_NAME = 6;
    /** A distinctive part shorter than this, its spaces dropped, is not looked for. */
    private static final int SHORTEST_PART = 3;
    /** One-word distinctive parts that are everyday words in a chat about orders: never looked for alone. */
    private static final Set<String> CHAT_WORDS = Set.of("DON", "HANG", "HOP", "DONG", "GIAO", "THUNG", "COC", "LY",
            "NAP", "IN", "MAU", "GIA", "TIEN", "NGAY", "DOT", "KHO", "VIET", "NAM", "SAI", "GON", "HA", "NOI");

    private final String customerAlias;
    private final List<Hidden> hidden;
    private final Map<String, String> revealed;

    private DraftNames(String customerAlias, List<Hidden> hidden, Map<String, String> revealed) {
        this.customerAlias = customerAlias;
        this.hidden = List.copyOf(hidden);
        this.revealed = Map.copyOf(revealed);
    }

    /**
     * @param customerName how the customer is named in the draft once revealed: its registered name
     * @param otherNames   other names it goes by (the company name of its legal profile); may hold blanks
     * @param contacts     its contact people's full names (its legal representative, its portal account's owner)
     */
    static DraftNames of(String customerName, Collection<String> otherNames,
                         Collection<String> contacts) {
        // One customer per draft: always the first alias, in the shape the chat's aliases have (Pseudonyms.ALIAS).
        String alias = Pseudonyms.alias(1);
        List<Hidden> hidden = new ArrayList<>();
        Map<String, String> revealed = new LinkedHashMap<>();
        if (customerName != null && !customerName.isBlank()) {
            revealed.put(alias, customerName.strip());
        }
        List<String> names = new ArrayList<>();
        names.add(customerName);
        names.addAll(otherNames);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Hidden whole = Hidden.whole(name, alias);
            if (whole != null) {
                hidden.add(whole);
            }
            Hidden part = Hidden.distinctive(name, alias);
            if (part != null) {
                hidden.add(part);
            }
        }
        int contact = 0;
        Set<String> seen = new java.util.HashSet<>();
        names.stream().filter(name -> name != null).forEach(name -> seen.add(TransferText.spaced(name)));
        for (String person : contacts) {
            // A contact who is the company itself (a bank account in its name), or one already given an alias.
            if (person == null || person.strip().length() < SHORTEST_NAME || !seen.add(TransferText.spaced(person))) {
                continue;
            }
            String contactAlias = "LH#" + (++contact);
            Hidden whole = Hidden.whole(person, contactAlias);
            if (whole != null) {
                hidden.add(whole);
                revealed.put(contactAlias, person.strip());
            }
        }
        hidden.sort(Comparator.comparingInt(Hidden::length).reversed());
        return new DraftNames(alias, hidden, revealed);
    }

    /** The customer's alias, as the facts name it; null for {@link #NONE}. */
    String customerAlias() {
        return customerAlias;
    }

    /** {@code text} with every name of the customer and of its contacts replaced by its alias; null stays null. */
    String hide(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = ALIAS_SHAPED.matcher(Normalizer.normalize(text, Normalizer.Form.NFC)).replaceAll("$1 ");
        for (Hidden name : hidden) {
            out = name.replace(out);
        }
        return out;
    }

    /** Puts the names back where the model wrote an alias; an alias given here to nobody stays as written. */
    UnaryOperator<String> revealer() {
        if (revealed.isEmpty()) {
            return UnaryOperator.identity();
        }
        return text -> {
            if (text == null) {
                return null;
            }
            String out = text;
            for (Map.Entry<String, String> alias : revealed.entrySet()) {
                String name = Pattern.quote(alias.getValue());
                String code = Pattern.quote(alias.getKey()) + "(?![\\p{L}\\p{N}])";
                // "Công ty ABC (KH#1)" or "KH#1 (Công ty ABC)" becomes the name once, as Pseudonyms.revealer does.
                out = Pattern.compile("(" + name + ")\\s*\\(\\s*" + code + "\\s*\\)|" + code + "\\s*\\(\\s*(" + name
                                + ")\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(out)
                        .replaceAll(pair -> Matcher.quoteReplacement(pair.group(1) != null ? pair.group(1)
                                : pair.group(2)));
                out = Pattern.compile("(?<![\\p{L}\\p{N}#])" + code, Pattern.CASE_INSENSITIVE).matcher(out)
                        .replaceAll(Matcher.quoteReplacement(alias.getValue()));
            }
            return out;
        };
    }

    /**
     * Each character upper case and without its diacritics, {@code đ} as {@code D}, one for one, so a match in the
     * folded text is a match at the same offsets in the text.
     */
    static String fold(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'đ' || c == 'Đ') {
                folded.append('D');
                continue;
            }
            String plain = c < 0x80 ? String.valueOf(c)
                    : Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            char base = plain.length() == 1 ? plain.charAt(0) : c;
            folded.append(Character.toUpperCase(base));
        }
        return folded.toString();
    }

    /**
     * One name looked for: its words in a row in the folded text, separated by anything that is not a letter or a
     * digit, not inside a longer word.
     *
     * @param capitalised a one-word distinctive part: found only where its text starts with a capital
     */
    private record Hidden(Pattern words, int length, String alias, boolean capitalised) {

        static Hidden whole(String name, String alias) {
            List<String> words = words(name);
            return name.strip().length() < SHORTEST_NAME || words.isEmpty() ? null
                    : new Hidden(pattern(words), String.join("", words).length(), alias, false);
        }

        static Hidden distinctive(String name, String alias) {
            List<String> words = TransferText.distinctive(name);
            String joined = String.join("", words);
            if (joined.length() < SHORTEST_PART || words.equals(words(name))) {
                return null;
            }
            boolean one = words.size() == 1;
            if (one && CHAT_WORDS.contains(words.getFirst())) {
                return null;
            }
            return new Hidden(pattern(words), joined.length(), alias, one);
        }

        String replace(String text) {
            Matcher found = words.matcher(fold(text));
            StringBuilder out = new StringBuilder(text.length());
            int kept = 0;
            while (found.find()) {
                if (capitalised && !Character.isUpperCase(text.charAt(found.start()))) {
                    continue;
                }
                out.append(text, kept, found.start()).append(alias);
                kept = found.end();
            }
            return kept == 0 ? text : out.append(text, kept, text.length()).toString();
        }

        private static List<String> words(String name) {
            String read = TransferText.spaced(name);
            return read.isEmpty() ? List.of() : List.of(read.split(" "));
        }

        private static Pattern pattern(List<String> words) {
            StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])");
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) {
                    regex.append("[^\\p{L}\\p{N}]+");
                }
                regex.append(Pattern.quote(words.get(i).toUpperCase(Locale.ROOT)));
            }
            return Pattern.compile(regex.append("(?![\\p{L}\\p{N}])").toString());
        }
    }
}

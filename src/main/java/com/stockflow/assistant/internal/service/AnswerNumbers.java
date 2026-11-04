package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numbers an answer states that nothing the model was given holds (design §7, §10.2): the golden set's
 * hallucination check, run on every live answer. The system prompt only asks that every number come from a tool
 * result or the context; this is how the panel learns that one did not, and says so under the answer instead of
 * showing it as fact beside the green tool chips.
 *
 * <p><b>Advisory, never a filter.</b> Nothing here removes or blocks anything: a flagged number is shown as written,
 * with the list beside it. The loop uses the same check to ask the model, once, to write the answer again
 * ({@code AssistantService}); whatever is still flagged after that is shown, flagged. It catches a figure no source
 * holds — a made-up revenue, a mis-copied quantity — not a true figure put on the wrong SKU, which the tables under
 * the answer cover ({@link ResultTable}).</p>
 *
 * <p><b>What counts as grounded</b> is {@code GoldenScorer}'s definition, so the eval gate and the panel agree:</p>
 * <ul>
 *   <li>a number some source holds: a tool result, the question or its context. Vietnamese and English separators
 *       are both read ({@code 1.276}, {@code 1,276}, {@code 12,5});</li>
 *   <li>what the prompt lets the model work out inside one record of a result ({@link #workedOut}): the rows of a
 *       list, a sum or difference of two of the record's numbers, ordered less shipped less closed, a total less the
 *       rows listed beside it. Arithmetic across records is not grounded, so a sum of unrelated figures is caught;</li>
 *   <li>numbers under 10 (day and month parts, small counts), years and list markers are not checked at all.</li>
 * </ul>
 *
 * <p><b>Digits inside a code ground only codes.</b> {@code SO-20991231-900101} in a result lets the answer write that
 * order number, and catches one mis-copied as {@code SO-20991231-900077}; it does not make "900101 cái" a quantity
 * someone looked up. A number of the answer that is itself part of something code-like — a code, digits joined by a
 * dash ({@code 20991231-900101}), digits against a letter ({@code 12oz}, {@code A01}) — is checked against every number,
 * so "cốc 12oz" read off {@code CUP-12OZ} stands; one that stands alone, against the numbers that stood alone in the
 * sources. A lone number that only a code holds ("lô 2401" for {@code EVAL-LOT-2401}) is flagged, and
 * {@link #check} says so, for the rewrite to write the code whole.</p>
 *
 * <p><b>Where it reads more than the scorer did, and why.</b> Amounts are written the way people say them: "35 triệu"
 * is 35,000,000, and the scorer flagged it because only its fact check knew the multipliers. A number counts as what
 * it was rounded to: "12,5%" for 12.46 and "125 triệu" for 124,800,000 are the figure at the precision written, not a
 * fabrication, while "128 triệu" for it still is. A JSON source's numbers are read as the values they are: {@code 1.5}
 * in a result no longer grounds a "15" in the answer, as reading it with Vietnamese separators would. And a token
 * read with thousands separators must have groups of three, so "12,5" is never also 125.</p>
 */
public final class AnswerNumbers {

    /** A number as written: digits with optional thousands or decimal separators. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    /** A lookahead, not {@code \b}: Java's word boundary is ASCII-only and does not end after "tỷ". */
    private static final Pattern MULTIPLIER = Pattern.compile(
            "\\s*(tỷ|ty|triệu|trieu|tr|nghìn|nghin|ngàn|ngan|k)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PERCENT = Pattern.compile("\\s*%");

    /**
     * A code in a source: segments joined by {@code -} or {@code _}, one of them holding two letters in a row
     * ({@code SO-20991231-900101}, {@code EVAL-LOT-2401}, {@code HCM-A01-1-B}). Not an ISO time
     * ({@code 2026-10-28T23:59}), whose one letter sits between digits.
     */
    private static final Pattern CODE = Pattern.compile("[\\p{L}\\p{N}]+(?:[-_][\\p{L}\\p{N}]+)+");
    private static final Pattern TWO_LETTERS = Pattern.compile("\\p{L}{2}");
    private static final Pattern YEAR = Pattern.compile("20\\d\\d");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal BILLION = new BigDecimal("1000000000");
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    /** Exact decimals: a source amount read as a double would no longer equal the amount written. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private AnswerNumbers() {
    }

    /** {@link #unverified(String, Grounds)} against {@code sources} read as {@link Grounds#of}. */
    public static List<String> unverified(String answer, Collection<String> sources) {
        return unverified(answer, Grounds.of(sources));
    }

    /**
     * The numbers of {@code answer} that {@code grounds} does not hold, each as written ({@code "128,4 triệu"},
     * {@code "12%"}), in the order they first appear, each once.
     */
    public static List<String> unverified(String answer, Grounds grounds) {
        return check(answer, grounds).stream().filter(checked -> !checked.grounded()).map(Checked::written).distinct()
                .toList();
    }

    /**
     * Every number of {@code answer} that is checked, in order, each time it appears: as written, whether
     * {@code grounds} holds it, and, for one it does not, whether a code of the sources holds its digits.
     */
    static List<Checked> check(String answer, Grounds grounds) {
        String text = normalise(answer);
        boolean[] codeLike = codeLike(text);
        List<Checked> checked = new ArrayList<>();
        Matcher number = NUMBER.matcher(text);
        while (number.find()) {
            String token = number.group();
            Matcher unit = MULTIPLIER.matcher(text).region(number.end(), text.length());
            boolean multiplied = unit.lookingAt();
            boolean inCode = partOfCode(text, number.start()) || codeLike[number.start()]
                    || !multiplied && number.end() < text.length() && Character.isLetter(text.charAt(number.end()));
            boolean abbreviated = !inCode && multiplied;
            BigDecimal factor = abbreviated ? factor(unit.group(1)) : BigDecimal.ONE;
            if (!abbreviated && (YEAR.matcher(token).matches() || listMarker(text, number.start(), number.end()))) {
                continue;
            }
            List<Reading> readings = readings(token);
            // No reading: a date written with dots (29.10.2026), a version; not a figure to check.
            if (readings.isEmpty()
                    || readings.stream().allMatch(reading -> reading.value().multiply(factor).compareTo(BigDecimal.TEN) < 0)) {
                continue;
            }
            boolean grounded = readings.stream().anyMatch(reading -> grounds.holds(reading.value().multiply(factor),
                    reading.tolerance().multiply(factor), inCode));
            int end = abbreviated ? unit.end() : number.end();
            Matcher percent = PERCENT.matcher(text).region(end, text.length());
            String written = text.substring(number.start(), !inCode && percent.lookingAt() ? percent.end() : end);
            boolean onlyInACode = !grounded && readings.stream().anyMatch(reading -> grounds.holds(
                    reading.value().multiply(factor), reading.tolerance().multiply(factor), true));
            checked.add(new Checked(written, grounded, onlyInACode));
        }
        return List.copyOf(checked);
    }

    /**
     * One number of an answer that was checked.
     *
     * @param written     as the answer writes it, with its unit or percent sign
     * @param grounded    whether something the model was given holds it
     * @param onlyInACode not grounded, but the digits of a code in a source are its value: written whole, the code
     *                    would be
     */
    record Checked(String written, boolean grounded, boolean onlyInACode) {
    }

    /**
     * What an answer may state: every number its sources hold, exactly and without its sign, and what may be worked
     * out inside one record of a JSON source. Built once per source set and combined with {@link #and}, so a transcript
     * keeps each exchange's grounds for the answers after it instead of reading the exchange again for each.
     */
    public static final class Grounds {

        private static final Grounds NONE = new Grounds(new TreeSet<>(), new TreeSet<>());

        /** Numbers that stood alone in a source. Compared with {@code compareTo}: 1.0 and 1.00 are one value. */
        private final NavigableSet<BigDecimal> values;

        /** Digit runs that were part of a code in a source ({@code SO-20991231-900101}): grounds for codes only. */
        private final NavigableSet<BigDecimal> codes;

        private Grounds(NavigableSet<BigDecimal> values, NavigableSet<BigDecimal> codes) {
            this.values = values;
            this.codes = codes;
        }

        public static Grounds none() {
            return NONE;
        }

        /**
         * @param sources tool results (JSON, or the {@code CODE: explanation} line of a failed call), the question and
         *                its context; null entries are skipped
         */
        public static Grounds of(Collection<String> sources) {
            NavigableSet<BigDecimal> values = new TreeSet<>();
            NavigableSet<BigDecimal> codes = new TreeSet<>();
            for (String source : sources) {
                if (source != null) {
                    read(source, values, codes);
                }
            }
            return values.isEmpty() && codes.isEmpty() ? NONE : new Grounds(values, codes);
        }

        /** Every number either holds. */
        public Grounds and(Grounds other) {
            if (other.values.isEmpty() && other.codes.isEmpty()) {
                return this;
            }
            if (values.isEmpty() && codes.isEmpty()) {
                return other;
            }
            NavigableSet<BigDecimal> both = new TreeSet<>(values);
            both.addAll(other.values);
            NavigableSet<BigDecimal> bothCodes = new TreeSet<>(codes);
            bothCodes.addAll(other.codes);
            return new Grounds(both, bothCodes);
        }

        /**
         * Whether some grounded value lies within {@code tolerance} of {@code value}, both ends included: among the
         * numbers that stood alone, and, for a number that is itself part of a code, among the codes' digits too.
         */
        boolean holds(BigDecimal value, BigDecimal tolerance, boolean inCode) {
            return near(values, value, tolerance) || inCode && near(codes, value, tolerance);
        }

        private static boolean near(NavigableSet<BigDecimal> set, BigDecimal value, BigDecimal tolerance) {
            BigDecimal nearest = set.ceiling(value.subtract(tolerance));
            return nearest != null && nearest.compareTo(value.add(tolerance)) <= 0;
        }
    }

    /** A JSON object or array is read as values; anything else, the question or an error line, as text. */
    private static void read(String source, Set<BigDecimal> values, Set<BigDecimal> codes) {
        JsonNode root = null;
        String trimmed = source.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                root = JSON.readTree(trimmed);
            } catch (JsonProcessingException notJson) {
                // Text that only looks like JSON, a question pasted with a brace: read as text below.
            }
        }
        if (root == null || !root.isContainerNode()) {
            text(source, values, codes);
        } else {
            collect(root, values, codes);
        }
    }

    /**
     * The numbers of a JSON tree: numbers as they are, text as text, and {@link #workedOut} for every record. Without
     * the sign, which an answer writes as a word ("giảm 12,5%" for -12.5, "quá hạn 15 ngày" for -15), never as a
     * minus that the answer's numbers would carry.
     */
    private static void collect(JsonNode node, Set<BigDecimal> values, Set<BigDecimal> codes) {
        if (node.isNumber()) {
            values.add(node.decimalValue().abs());
        } else if (node.isTextual()) {
            text(node.textValue(), values, codes);
        } else if (node.isArray()) {
            values.add(BigDecimal.valueOf(node.size()));
            node.forEach(child -> collect(child, values, codes));
        } else if (node.isObject()) {
            workedOut(node, values);
            node.forEach(child -> collect(child, values, codes));
        }
    }

    /**
     * What the prompt lets the model work out from one record: the sum or difference of two of its numbers, one less
     * two others (units still to come are ordered - shipped - closed) and one less the rows of a list beside it (how
     * many a total leaves out). The rows of each list are counted in {@link #collect}. Two, and two others, are
     * distinct fields: a figure doubled is not a sum the prompt allows, and with rounding read generously
     * ({@link Reading}) it would ground far too much.
     */
    private static void workedOut(JsonNode record, Set<BigDecimal> values) {
        List<BigDecimal> numbers = new ArrayList<>();
        List<BigDecimal> rows = new ArrayList<>();
        record.forEach(field -> {
            if (field.isNumber()) {
                numbers.add(field.decimalValue());
            } else if (field.isArray()) {
                rows.add(BigDecimal.valueOf(field.size()));
            }
        });
        for (int i = 0; i < numbers.size(); i++) {
            BigDecimal a = numbers.get(i);
            rows.forEach(count -> values.add(a.subtract(count).abs()));
            for (int j = 0; j < numbers.size(); j++) {
                if (j == i) {
                    continue;
                }
                BigDecimal b = numbers.get(j);
                values.add(a.add(b).abs());
                values.add(a.subtract(b).abs());
                for (int k = 0; k < numbers.size(); k++) {
                    if (k != i && k != j) {
                        values.add(a.subtract(b).subtract(numbers.get(k)).abs());
                    }
                }
            }
        }
    }

    /**
     * Every number in free text, in every reading its separators allow, and multiplied when a unit follows; a number
     * inside a code goes to {@code codes} alone (class javadoc).
     */
    private static void text(String source, Set<BigDecimal> values, Set<BigDecimal> codes) {
        String text = normalise(source);
        boolean[] code = codeCharacters(text);
        Matcher number = NUMBER.matcher(text);
        while (number.find()) {
            List<Reading> readings = readings(number.group());
            if (code[number.start()] || afterALetter(text, number.start())) {
                readings.forEach(reading -> codes.add(reading.value()));
                continue;
            }
            readings.forEach(reading -> values.add(reading.value()));
            Matcher unit = MULTIPLIER.matcher(text).region(number.end(), text.length());
            if (unit.lookingAt()) {
                BigDecimal factor = factor(unit.group(1));
                readings.forEach(reading -> values.add(reading.value().multiply(factor)));
            }
        }
    }

    /**
     * How a token may be read. Without separators it is the number. With them, it is a whole number when every
     * separator is the same and groups three digits ({@code 1.276}, {@code 12.500.000}), and a decimal when its last
     * separator appears once and any before it group thousands ({@code 12,5}, {@code 1.250,5}); {@code 1.250} is both,
     * and the sources decide.
     */
    static List<Reading> readings(String token) {
        int last = Math.max(token.lastIndexOf('.'), token.lastIndexOf(','));
        if (last < 0) {
            return List.of(new Reading(new BigDecimal(token), 0));
        }
        List<Reading> readings = new ArrayList<>(2);
        if (grouped(token)) {
            readings.add(new Reading(new BigDecimal(digits(token)), 0));
        }
        String whole = token.substring(0, last);
        if (whole.indexOf(token.charAt(last)) < 0 && grouped(whole)) {
            String fraction = token.substring(last + 1);
            readings.add(new Reading(new BigDecimal(digits(whole) + "." + fraction), fraction.length()));
        }
        return readings;
    }

    /** Separators that all are one character, with one to three digits before the first and three after each. */
    private static boolean grouped(String token) {
        char separator = 0;
        int group = -1;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '.' && c != ',') {
                continue;
            }
            if (group < 0 ? i > 3 : i - group != 3 || c != separator) {
                return false;
            }
            separator = c;
            group = i + 1;
        }
        return group < 0 || token.length() - group == 3;
    }

    private static String digits(String token) {
        return token.replace(".", "").replace(",", "");
    }

    private static BigDecimal factor(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "tỷ", "ty" -> BILLION;
            case "triệu", "trieu", "tr" -> MILLION;
            default -> THOUSAND;
        };
    }

    /**
     * Digits inside a code ({@code CUP-12K}, {@code SO-20991231-900001}): checked as written, never read as an amount
     * in thousands or a percent.
     */
    private static boolean partOfCode(String text, int start) {
        if (start == 0) {
            return false;
        }
        char before = text.charAt(start - 1);
        return Character.isLetter(before)
                || (before == '-' || before == '_') && start > 1 && Character.isLetterOrDigit(text.charAt(start - 2));
    }

    /**
     * Which characters of an answer lie in something code-like: segments joined by {@code -} or {@code _}, whatever
     * they hold. Looser than a source's {@link #codeCharacters} on purpose: a number marked here is only checked
     * against more of what the sources hold, never against less.
     */
    private static boolean[] codeLike(String text) {
        boolean[] code = new boolean[text.length() + 1];
        Matcher match = CODE.matcher(text);
        while (match.find()) {
            java.util.Arrays.fill(code, match.start(), match.end(), true);
        }
        return code;
    }

    /** Which characters of a source lie inside a {@link #CODE}. */
    private static boolean[] codeCharacters(String text) {
        boolean[] code = new boolean[text.length() + 1];
        Matcher match = CODE.matcher(text);
        while (match.find()) {
            if (TWO_LETTERS.matcher(match.group()).find()) {
                java.util.Arrays.fill(code, match.start(), match.end(), true);
            }
        }
        return code;
    }

    /**
     * Digits right after a letter that does not itself follow a digit: {@code A01}, {@code CUP12}; not the {@code T}
     * of an ISO time ({@code 28T23}).
     */
    private static boolean afterALetter(String text, int start) {
        return start > 0 && Character.isLetter(text.charAt(start - 1))
                && (start < 2 || !Character.isDigit(text.charAt(start - 2)));
    }

    /** {@code 10.} or {@code 10)} opening a line: a list item's number, not a figure. */
    private static boolean listMarker(String text, int start, int end) {
        if (end >= text.length() || (text.charAt(end) != '.' && text.charAt(end) != ')')
                || end + 1 < text.length() && !Character.isWhitespace(text.charAt(end + 1))) {
            return false;
        }
        for (int i = start - 1; i >= 0 && text.charAt(i) != '\n'; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String normalise(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC).replace(' ', ' ')
                .replace(' ', ' ');
    }

    /**
     * One way to read a token.
     *
     * @param decimals digits after the decimal separator: the number counts as any value it rounds to at that
     *                 precision, half a unit of its last digit either way
     */
    record Reading(BigDecimal value, int decimals) {

        BigDecimal tolerance() {
            return HALF.movePointLeft(decimals);
        }
    }
}

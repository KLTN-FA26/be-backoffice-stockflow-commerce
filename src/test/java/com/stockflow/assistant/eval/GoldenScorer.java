package com.stockflow.assistant.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mechanical checks of design §10.2. No model grades another model here: every check is string and number
 * matching, so a score can be argued with and reproduced.
 *
 * <p>Answers are written for people, in Vietnamese: {@code 1.000} or {@code 1,000} for a thousand, {@code 12,5 triệu}
 * for 12,500,000, {@code 04/11/2026} for a date. A fact matches any of those forms of itself.</p>
 */
public final class GoldenScorer {

    /** A number as written: digits with optional thousands or decimal separators. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    /** A lookahead, not {@code \b}: Java's word boundary is ASCII-only and does not end after "tỷ". */
    private static final Pattern MULTIPLIER = Pattern.compile(
            "\\s*(tỷ|ty|triệu|trieu|tr|nghìn|nghin|ngàn|ngan|k)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private GoldenScorer() {
    }

    /** Whether {@code answer} states {@code fact}: a code or name as text, a number or a date in any usual form. */
    public static boolean states(String answer, String fact) {
        String text = normalise(answer);
        String value = normalise(fact).strip();
        if (value.isEmpty()) {
            return true;
        }
        if (ISO_DATE.matcher(value).matches()) {
            return statesDate(text, value);
        }
        BigDecimal number = number(value);
        if (number != null) {
            return numbers(text).stream().anyMatch(candidate -> candidate.compareTo(number) == 0)
                    || abbreviated(text).stream().anyMatch(candidate -> close(candidate, number));
        }
        return text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    /**
     * Numbers in {@code answer} that no source contains: the hallucination check. Numbers under 10 (list markers, day
     * and month parts), years and the number formats of a date are not counted. What the system prompt lets the model
     * work out counts as grounded too, but only inside one record of a tool result ({@link #workedOut}), so a number
     * made up from unrelated figures is still caught.
     *
     * @param sources the tool results of the turn, the question and its context
     */
    public static List<String> ungroundedNumbers(String answer, List<String> sources) {
        Set<BigDecimal> grounded = new HashSet<>();
        for (String source : sources) {
            grounded.addAll(numbers(normalise(source)));
            grounded.addAll(workedOut(source));
        }
        List<String> ungrounded = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(normalise(answer));
        while (matcher.find()) {
            String token = matcher.group();
            List<BigDecimal> readings = readings(token);
            boolean small = readings.stream().allMatch(reading -> reading.compareTo(BigDecimal.TEN) < 0);
            boolean year = token.matches("20\\d\\d");
            boolean known = readings.stream().anyMatch(reading -> grounded.stream()
                    .anyMatch(source -> source.compareTo(reading) == 0));
            if (!small && !year && !known) {
                ungrounded.add(token);
            }
        }
        return ungrounded;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The numbers the model may work out from one tool result: the rows of a list, and inside one record the sum or
     * difference of two of its numbers, one less two others (units still to come are ordered - shipped - closed) and
     * one less the rows of a list beside it (how many a total leaves out). Empty for a source that is not JSON.
     */
    static Set<BigDecimal> workedOut(String source) {
        JsonNode root;
        try {
            root = JSON.readTree(source);
        } catch (JsonProcessingException notJson) {
            return Set.of();
        }
        Set<BigDecimal> worked = new HashSet<>();
        if (root != null) {
            collect(root, worked);
        }
        return worked;
    }

    private static void collect(JsonNode node, Set<BigDecimal> worked) {
        if (node.isArray()) {
            worked.add(BigDecimal.valueOf(node.size()));
        }
        if (node.isObject()) {
            List<BigDecimal> values = new ArrayList<>();
            List<BigDecimal> rows = new ArrayList<>();
            node.forEach(field -> {
                if (field.isNumber()) {
                    values.add(field.decimalValue());
                } else if (field.isArray()) {
                    rows.add(BigDecimal.valueOf(field.size()));
                }
            });
            for (BigDecimal a : values) {
                rows.forEach(count -> worked.add(a.subtract(count)));
                for (BigDecimal b : values) {
                    worked.add(a.add(b));
                    worked.add(a.subtract(b).abs());
                    values.forEach(c -> worked.add(a.subtract(b).subtract(c)));
                }
            }
        }
        node.forEach(child -> collect(child, worked));
    }

    private static final Pattern RULE = Pattern.compile("BR-[A-Z]+-\\d+[a-z]?", Pattern.CASE_INSENSITIVE);

    /** The business-rule ids an answer names ({@code BR-RMA-002}), each once, upper case. */
    public static List<String> namedRules(String answer) {
        Set<String> named = new java.util.LinkedHashSet<>();
        Matcher matcher = RULE.matcher(normalise(answer));
        while (matcher.find()) {
            named.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(named);
    }

    static String normalise(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC).replace(' ', ' ');
    }

    /** Every number in the text, each in every reading its separators allow. */
    static Set<BigDecimal> numbers(String text) {
        Set<BigDecimal> found = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            found.addAll(readings(matcher.group()));
        }
        return found;
    }

    /** {@code 1.250} is 1250 or 1.25; {@code 12,5} is 12.5 or 125; both are offered and the fact decides. */
    static List<BigDecimal> readings(String token) {
        List<BigDecimal> readings = new ArrayList<>();
        readings.add(new BigDecimal(token.replace(".", "").replace(",", "")));
        for (char decimal : new char[] {',', '.'}) {
            int last = token.lastIndexOf(decimal);
            if (last > 0) {
                String whole = token.substring(0, last).replace(".", "").replace(",", "");
                readings.add(new BigDecimal(whole + "." + token.substring(last + 1)));
            }
        }
        return readings;
    }

    /** "12,5 triệu" as 12,500,000 — with how precisely it was written, for {@link #close}. */
    private static List<BigDecimal[]> abbreviated(String text) {
        List<BigDecimal[]> found = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            Matcher unit = MULTIPLIER.matcher(text.substring(matcher.end()));
            if (!unit.lookingAt()) {
                continue;
            }
            BigDecimal factor = switch (unit.group(1).toLowerCase(Locale.ROOT)) {
                case "tỷ", "ty" -> new BigDecimal("1000000000");
                case "triệu", "trieu", "tr" -> new BigDecimal("1000000");
                default -> new BigDecimal("1000");
            };
            for (BigDecimal reading : readings(matcher.group())) {
                // Written to one decimal of the unit: accept anything that rounds to it.
                found.add(new BigDecimal[] {reading.multiply(factor), factor.divide(BigDecimal.valueOf(20))});
            }
        }
        return found;
    }

    private static boolean close(BigDecimal[] candidate, BigDecimal fact) {
        return candidate[0].subtract(fact).abs().compareTo(candidate[1]) <= 0;
    }

    private static boolean statesDate(String text, String iso) {
        if (text.contains(iso)) {
            return true;
        }
        try {
            LocalDate date = LocalDate.parse(iso);
            String day = String.valueOf(date.getDayOfMonth());
            String month = String.valueOf(date.getMonthValue());
            String dd = "%02d".formatted(date.getDayOfMonth());
            String mm = "%02d".formatted(date.getMonthValue());
            for (String form : List.of(dd + "/" + mm + "/" + date.getYear(), day + "/" + month + "/" + date.getYear(),
                    dd + "/" + mm, day + "/" + month, dd + "-" + mm + "-" + date.getYear(),
                    "ngày " + day + " tháng " + month)) {
                if (text.contains(form)) {
                    return true;
                }
            }
            return false;
        } catch (DateTimeParseException notADate) {
            return text.contains(iso);
        }
    }

    private static BigDecimal number(String value) {
        if (!value.matches("-?\\d+(\\.\\d+)?")) {
            return null;
        }
        return new BigDecimal(value);
    }
}

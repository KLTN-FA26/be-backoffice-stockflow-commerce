package com.stockflow.assistant.internal.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The codes an answer writes that nothing the model was given holds: a SKU, an order, lot, bin or carton code no tool
 * returned, no question typed and no context named. The companion of {@link AnswerNumbers}, with the same grounds and
 * the same advisory standing: nothing is removed, the code is shown as written with the list beside it, and the loop
 * uses the check to have the model look up or write the answer again ({@code AssistantService}).
 *
 * <p><b>Why codes, and not only numbers.</b> A model that answers without looking anything up writes codes as readily
 * as figures: "Có 2 mã dưới điểm đặt hàng: CUP-A và LID-B" was a small model's whole answer, copied from the system
 * prompt's example, and no number in it was large enough for {@link AnswerNumbers} to look at. A code is what the
 * reader acts on — the SKU they reorder, the order they chase — so one nobody looked up is the most expensive thing an
 * answer can invent.</p>
 *
 * <p><b>What is a code here.</b> Two or more parts joined by dashes, each part capitals and digits, with at least one
 * capital: {@code SO-20991231-900101}, {@code EVAL-CUP-12OZ}, {@code HCM-A01-1-B}, {@code L-7}. Not checked: anything
 * with a lower-case part (a permission such as {@code reporting-reports:READ}, a word with a hyphen), a status joined by
 * underscores, digits alone (a date), an ISO time, a date format ({@code YYYY-MM-DD}), and the ids of rules and flows
 * ({@code BR-PAY-008}, {@code F-PO}), which the tool descriptions name and the citation check covers
 * ({@link AnswerSources#citations}). A placeholder of the system prompt's examples ({@code «SKU 1»}) copied into an
 * answer is always flagged.</p>
 *
 * <p><b>Grounded</b> means the whole code appears, in any case and with any dash, in a result or the question, or is
 * one of its leading parts of two segments or more: {@code HCM-A01} for a bin {@code HCM-A01-1-B} is the rack the bin
 * is on. A code mis-copied by one digit is not grounded, which is the point. The leading parts are the accepted gap: a
 * SKU {@code CUP-12OZ} never looked up passes when {@code CUP-12OZ-WHT} was. A refused or failed call's line grounds
 * no code ({@link AnswerNumbers.Grounds}).</p>
 */
public final class AnswerCodes {

    /**
     * A code as an answer writes it, not inside a longer token on either side: a dash or a letter right before or
     * after it would make it part of a word or a lower-case code.
     */
    static final Pattern CODE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_-])[\\p{Lu}\\p{N}]+(?:-[\\p{Lu}\\p{N}]+)+(?![\\p{L}\\p{N}_]|-[\\p{L}\\p{N}])");

    private static final Pattern CAPITAL = Pattern.compile("\\p{Lu}");

    /** A rule, flow or exception flow: a citation, checked as one. */
    private static final Pattern ANCHOR = Pattern.compile("(?:BR|F|X)-[\\p{Lu}\\p{N}-]+");

    /** {@code YYYY-MM-DD}, as a tool description writes a date's format: not a code. */
    private static final Pattern DATE_FORMAT = Pattern.compile(
            "(?:Y{2,4}|M{1,2}|D{1,2})(?:-(?:Y{2,4}|M{1,2}|D{1,2}))+");

    /**
     * A part of the system prompt's examples that stands for data ({@code «SKU 1»}, {@code «số đơn»}). Guillemets,
     * because {@code AnswerText} keeps them: angle brackets would be removed as a tag before any check saw them. An
     * answer that writes one gave the example as its answer, and is treated as writing a code nobody looked up.
     */
    static final Pattern PLACEHOLDER = Pattern.compile("«[^«»\n]{1,24}»");

    /** {@code 2026-10-28T23}: one capital between digits, a time and not a code. */
    private static final Pattern ISO_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d+");

    /**
     * A code as a source may write it, any case: grounds are read loosely on purpose, since a code they hold only
     * lets the answer write it, never adds a claim.
     */
    private static final Pattern SOURCE_CODE = Pattern.compile("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)+");

    private AnswerCodes() {
    }

    /** The codes of {@code answer} that {@code grounds} does not hold, as written, in order, each once. */
    public static List<String> unverified(String answer, AnswerNumbers.Grounds grounds) {
        return check(answer, grounds).stream().filter(checked -> !checked.grounded()).map(Checked::written)
                .distinct().toList();
    }

    /**
     * Every code {@code answer} writes, in order, each once, and whether {@code grounds} holds it; then every
     * placeholder of the prompt's examples it copied, which nothing holds.
     */
    static List<Checked> check(String answer, AnswerNumbers.Grounds grounds) {
        List<Checked> checked = new java.util.ArrayList<>(written(answer).stream()
                .map(code -> new Checked(code, grounds.holdsCode(code))).toList());
        Matcher placeholder = PLACEHOLDER.matcher(normalise(answer));
        while (placeholder.find()) {
            checked.add(new Checked(placeholder.group(), false));
        }
        return List.copyOf(checked);
    }

    /** The codes {@code text} writes ({@link #CODE}), in order, each once. */
    static List<String> written(String text) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher match = CODE.matcher(normalise(text));
        while (match.find()) {
            if (checked(match.group())) {
                codes.add(match.group());
            }
        }
        return List.copyOf(codes);
    }

    /**
     * Which characters of {@code text} lie in a code this class checks: the digits there are its to judge, so
     * {@link AnswerNumbers} does not flag them a second time. {@code text} must be normalised as both classes do.
     */
    static boolean[] spans(String text) {
        boolean[] code = new boolean[text.length() + 1];
        Matcher match = CODE.matcher(text);
        while (match.find()) {
            if (checked(match.group())) {
                Arrays.fill(code, match.start(), match.end(), true);
            }
        }
        return code;
    }

    /**
     * The codes a source writes, upper-cased, each with its leading parts of two segments or more
     * ({@code HCM-A01-1-B} gives {@code HCM-A01} and {@code HCM-A01-1} too).
     */
    static void collect(String source, Set<String> into) {
        Matcher match = SOURCE_CODE.matcher(normalise(source));
        while (match.find()) {
            String[] parts = match.group().toUpperCase(Locale.ROOT).split("-");
            StringBuilder code = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                code.append('-').append(parts[i]);
                into.add(code.toString());
            }
        }
    }

    private static boolean checked(String code) {
        return CAPITAL.matcher(code).find() && !ANCHOR.matcher(code).matches() && !ISO_TIME.matcher(code).matches()
                && !DATE_FORMAT.matcher(code).matches();
    }

    private static String normalise(String text) {
        return AnswerNumbers.normalise(text);
    }

    /**
     * One code of an answer that was checked.
     *
     * @param written  as the answer writes it
     * @param grounded whether something the model was given holds it
     */
    record Checked(String written, boolean grounded) {
    }
}

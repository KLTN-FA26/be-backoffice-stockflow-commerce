package com.stockflow.chat.internal.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks what must never sit in a conversation (kltn-docs 16 BR-08: card numbers and passwords are not asked for or
 * typed in the chat, and the system masks them). Applied to every text a person writes - a message, a note, a caption,
 * an edit - <b>before it is stored</b>: a mask applied only when showing would leave the number in the database, its
 * backups and the search index.
 *
 * <ul>
 *   <li><b>Card numbers</b>: 13 to 19 digits, spaces or dashes allowed between them, that pass the Luhn check - the
 *       check is what keeps an order number, a phone number or an amount from being masked. All but the last four
 *       digits become {@code *}.</li>
 *   <li><b>CVV / CVC</b>: three or four digits after the word ({@code CVV: 123}).</li>
 *   <li><b>Passwords and one-time codes</b>: whatever follows "password", "mật khẩu", "mk", "pass", "OTP" and a
 *       {@code :}, {@code =} or "là" / "is" ({@code mật khẩu là abc123}).</li>
 * </ul>
 *
 * <p>Deliberately simple patterns: a false positive costs a few hidden characters, a miss costs a leaked card. A
 * bank account number is not masked - the company's own is what the transfer card shows (BR-06).</p>
 */
public final class SensitiveData {

    /**
     * Digits not glued to a letter or a dash on either side: {@code SO-20991231-000001} and {@code HD-...} are document
     * numbers, never cards, even when their digits happen to pass the Luhn check.
     */
    private static final Pattern CARD = Pattern.compile("(?<![\\d\\p{L}-])(?:\\d[ -]?){12,18}\\d(?![\\d\\p{L}-])");
    private static final Pattern CVV = Pattern.compile(
            "(?iu)\\b(cvv2?|cvc2?|cvn|csc)(\\s*(?:[:=]|là|la|is)?\\s*)(\\d{3,4})\\b");
    private static final Pattern SECRET = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])(password|passwd|pwd|pass|mật khẩu|mat khau|mk|otp|mã otp|ma otp)"
                    + "(\\s*(?:[:=]|\\s(?:là|la|is)\\s)\\s*)(\\S+)");
    private static final String HIDDEN = "********";

    private SensitiveData() {
    }

    /** {@code text} with every card number, CVV, password and one-time code in it masked; null stays null. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = maskCards(text);
        masked = CVV.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2)
                + "*".repeat(m.group(3).length())));
        masked = SECRET.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + HIDDEN));
        return masked;
    }

    /** Whether {@code text} holds anything {@link #mask} would hide. */
    public static boolean holdsSensitive(String text) {
        return text != null && !text.equals(mask(text));
    }

    private static String maskCards(String text) {
        Matcher m = CARD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = m.group();
            String digits = found.replaceAll("[ -]", "");
            String replacement = digits.length() >= 13 && digits.length() <= 19 && luhn(digits)
                    ? hideAllButLastFour(found) : found;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The digits of {@code found} but the last four become {@code *}; the separators stay where they were. */
    private static String hideAllButLastFour(String found) {
        int digits = (int) found.chars().filter(Character::isDigit).count();
        StringBuilder out = new StringBuilder(found.length());
        int seen = 0;
        for (char c : found.toCharArray()) {
            if (Character.isDigit(c)) {
                seen++;
                out.append(seen > digits - 4 ? c : '*');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}

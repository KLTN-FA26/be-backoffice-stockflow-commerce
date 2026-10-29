package com.stockflow.assistant.internal.domain;

import java.util.regex.Pattern;

/**
 * The answer as the panel may render it: links, images, bare URLs and HTML tags removed, their visible words kept,
 * and nothing left from which a renderer could build a link, an image or a tag.
 *
 * <p>No tool returns a URL, so an answer that holds one put it there because some text asked it to — a customer's
 * cancellation note read by {@code findOrder}, say. A Markdown image is fetched the moment it is rendered, with
 * whatever the model wrote into its address: revenue, supplier totals, other customers' order numbers. The system
 * prompt forbids links too; this is the part that does not depend on the model obeying.</p>
 *
 * <p><b>The patterns tidy; the escaping protects.</b> A pattern cannot follow Markdown: brackets nest
 * ({@code ![a [b] c](…)}) and escape each other ({@code ![a\]b](…)}), and a reference definition may sit inside a quote
 * or a list item. So the patterns only turn the plain forms into their words, and the last step escapes every bracket.
 * Every link, image, image reference and reference definition starts with one; with none left live, none can form,
 * however the rest is written.</p>
 *
 * <p><b>What is rendered is what was checked.</b> A renderer decodes before it links: {@code https&#58;//} and
 * {@code https:\/\/} both become an address that GFM turns into a link, past a pattern that read the text as written.
 * So the last step also doubles every backslash, which makes each one literal, and escapes the {@code &} of anything
 * shaped like a character reference: the text a renderer works on is then the text the URL pattern saw. Doubling
 * shows a backslash the model meant as an escape ({@code \*}), which costs nothing worse than the backslash. For the
 * same reason tags go before URLs ({@code https:<b>//} would otherwise rejoin), and a {@code <} that could still open
 * a tag ({@code <<b>img …>} loses its inner tag and becomes one) is escaped.</p>
 */
public final class AnswerText {

    static final String URL_REMOVED = "[link removed]";

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern REFERENCE = Pattern.compile("(?m)^\\s*\\[[^\\]]+]:\\s*\\S+.*$");
    private static final Pattern AUTOLINK = Pattern.compile("<(?:https?|ftp|mailto|data|javascript):[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("(?:https?|ftp)://\\S+|(?:data|javascript):\\S+|www\\.\\S+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    /**
     * What follows the {@code &} of a character reference, which a renderer decodes: {@code &#58;}, {@code &#x3A;},
     * {@code &colon;}. {@code R&D} is no reference and stays as it is.
     */
    private static final Pattern CHARACTER_REFERENCE = Pattern.compile(
            "#[0-9]{1,7};|#[xX][0-9a-fA-F]{1,6};|[A-Za-z][A-Za-z0-9]{0,31};");

    private AnswerText() {
    }

    /** {@code text} with nothing a renderer would fetch or follow; null stays null. */
    public static String plain(String text) {
        if (text == null) {
            return null;
        }
        String plain = IMAGE.matcher(text).replaceAll("$1");
        plain = LINK.matcher(plain).replaceAll("$1");
        plain = REFERENCE.matcher(plain).replaceAll("");
        plain = AUTOLINK.matcher(plain).replaceAll(URL_REMOVED);
        plain = TAG.matcher(plain).replaceAll("");
        plain = URL.matcher(plain).replaceAll(URL_REMOVED);
        // Last, so that what the steps above inserted ("[link removed]") or exposed is escaped as well.
        return escape(plain);
    }

    /**
     * Every backslash doubled, every bracket escaped, and the {@code &} of a character reference and a {@code <} that
     * could open a tag, a comment or an autolink escaped. Nothing is left for a renderer to decode or to link.
     */
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean markup = c == '\\' || c == '[' || c == ']'
                    || c == '<' && opensTag(text, i + 1)
                    || c == '&' && CHARACTER_REFERENCE.matcher(text).region(i + 1, text.length()).lookingAt();
            if (markup) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** Whether the character at {@code next} may follow a {@code <} that opens one; in {@code 3 < 5} it does not. */
    private static boolean opensTag(String text, int next) {
        if (next >= text.length()) {
            return false;
        }
        char c = text.charAt(next);
        return c == '/' || c == '!' || c == '?' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }
}

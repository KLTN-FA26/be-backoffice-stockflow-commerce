package com.stockflow.assistant.internal.domain;

import java.util.regex.Pattern;

/**
 * The answer as the panel may render it: links, images, bare URLs and HTML tags removed, their visible words kept.
 *
 * <p>No tool returns a URL, so an answer that holds one put it there because some text asked it to — a customer's
 * cancellation note read by {@code findOrder}, say. A Markdown image is fetched the moment it is rendered, with
 * whatever the model wrote into its address: revenue, supplier totals, other customers' order numbers. The system
 * prompt forbids links too; this is the part that does not depend on the model obeying.</p>
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
        plain = URL.matcher(plain).replaceAll(URL_REMOVED);
        return TAG.matcher(plain).replaceAll("");
    }
}

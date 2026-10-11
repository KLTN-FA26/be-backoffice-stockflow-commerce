package com.stockflow.common.logging;

/**
 * Makes a value from outside — a header, a path, a file name, a message carrying either — safe to put
 * in a log line (CodeQL {@code java/log-injection}).
 *
 * <p>A line break in such a value would let whoever sent it write a line of their own into the log,
 * one an operator would read as the application's: {@code "\r\n2026-10-11 INFO Released order ... by
 * admin"}. Line breaks and every other control character become {@code _}, and the value is cut at
 * {@value #MAX_LENGTH} characters so one request cannot flood a log line either.</p>
 */
public final class LogSafe {

    static final int MAX_LENGTH = 300;

    private LogSafe() {
    }

    /** {@code value} as one harmless line; null stays null. */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        String oneLine = String.valueOf(value).replace("\n", "_").replace("\r", "_");
        StringBuilder safe = new StringBuilder(Math.min(oneLine.length(), MAX_LENGTH + 1));
        for (int i = 0; i < oneLine.length() && safe.length() < MAX_LENGTH; i++) {
            char c = oneLine.charAt(i);
            safe.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? '_' : c);
        }
        return oneLine.length() > MAX_LENGTH ? safe.append('…').toString() : safe.toString();
    }
}

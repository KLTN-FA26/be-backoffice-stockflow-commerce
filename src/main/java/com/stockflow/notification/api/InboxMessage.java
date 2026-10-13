package com.stockflow.notification.api;

import java.util.Objects;

/**
 * One in-app message, as the posting module words it. The same message goes to every recipient of one
 * {@link Inbox#post} call.
 *
 * <p><b>Only system-generated text.</b> {@code title} and {@code body} are built by the poster from codes,
 * ids, counts and dates ("12 lots expire within 30 days at HCM-01"); they must not carry what a customer
 * or an operator typed (names, notes, reasons), which is why the limits are small. The inbox does not
 * log them either.</p>
 *
 * @param category   a code of the poster's own, up to 40 characters ({@code "INVENTORY_ALERT"}); the bell
 *                   groups and icons by it
 * @param severity   how loudly to show it
 * @param title      one short line, up to 200 characters
 * @param body       optional detail, up to 1000 characters
 * @param link       optional route of this application the message opens, up to 300 characters and
 *                   starting with a single {@code /}; never a full URL, because the bell turns it into a
 *                   navigation
 * @param sourceType what the message is about ({@code "STOCK_ALERT"}), optional, up to 40 characters
 * @param sourceId   id of that thing, optional, up to 100 characters
 * @param dedupeKey  posting the same key to the same recipient again is a no-op, up to 200 characters; it
 *                   decides how often someone can be told the same thing
 */
public record InboxMessage(String category, InboxSeverity severity, String title, String body, String link,
                           String sourceType, String sourceId, String dedupeKey) {

    public static final int CATEGORY_MAX = 40;
    public static final int TITLE_MAX = 200;
    public static final int BODY_MAX = 1000;
    public static final int LINK_MAX = 300;
    public static final int SOURCE_TYPE_MAX = 40;
    public static final int SOURCE_ID_MAX = 100;
    public static final int DEDUPE_KEY_MAX = 200;

    /**
     * Refuses a message the table would refuse, at the poster's call site rather than as a constraint
     * violation inside its transaction. A poster that builds a too-long title has a bug to fix, so this
     * throws instead of truncating.
     */
    public InboxMessage {
        category = required("category", category, CATEGORY_MAX);
        Objects.requireNonNull(severity, "severity");
        title = required("title", title, TITLE_MAX);
        body = optional("body", body, BODY_MAX);
        link = optional("link", link, LINK_MAX);
        if (link != null && (!link.startsWith("/") || link.startsWith("//"))) {
            throw new IllegalArgumentException("link must be a route of this application starting with a single '/'");
        }
        sourceType = optional("sourceType", sourceType, SOURCE_TYPE_MAX);
        sourceId = optional("sourceId", sourceId, SOURCE_ID_MAX);
        dedupeKey = required("dedupeKey", dedupeKey, DEDUPE_KEY_MAX);
    }

    private static String required(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return optional(field, value, max);
    }

    private static String optional(String field, String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " is longer than " + max + " characters");
        }
        return trimmed;
    }
}

package com.stockflow.chat.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A saved reply of Sales ({@code chat.canned_response}): an agent's own, or one shared with every agent. Its body may
 * name {@link #VARIABLES} in braces - {@code {customerName}}, {@code {quoteCode}}... - which the server fills in from
 * the conversation it is sent in ({@link #render}), so the agent never retypes a number and never sends the wrong one.
 *
 * <p>Who may change it: its owner, for their own; a chat supervisor, for a shared one (16 §2: the supervisor sets the
 * team's way of answering). Deleting archives it, so the shortcut is free again and nothing that cites it breaks.</p>
 */
public record CannedResponse(UUID id, UUID ownerId, String title, String body, String shortcut, String category,
                             Instant archivedAt, long version) {

    public static final int MAX_TITLE = 200;
    public static final int MAX_BODY = 4000;
    public static final int MAX_CATEGORY = 64;
    private static final Pattern SHORTCUT = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z]+)}");

    /**
     * The variables a body may name, each filled from the conversation: the customer, the agent sending it, and the
     * number of the latest document of each kind the conversation is about. One that has nothing to fill it is left
     * empty; a name not in this list is left as it was written.
     */
    public static final java.util.List<String> VARIABLES = java.util.List.of("customerName", "agentName", "quoteCode",
            "orderCode", "contractCode", "productionOrderCode", "invoiceCode", "designName");

    public CannedResponse {
        Objects.requireNonNull(id, "id");
        title = required(title, MAX_TITLE, "title");
        body = required(body, MAX_BODY, "body");
        shortcut = shortcut == null || shortcut.isBlank() ? null : shortcut.strip().toLowerCase(java.util.Locale.ROOT);
        if (shortcut != null && !SHORTCUT.matcher(shortcut).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A shortcut is 1 to 32 lower-case letters, digits, '-' or '_', starting with a letter or digit");
        }
        category = category == null || category.isBlank() ? null : category.strip();
        if (category != null && category.length() > MAX_CATEGORY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A category is at most " + MAX_CATEGORY
                    + " characters");
        }
    }

    /** Whether every agent sees it, rather than its owner alone. */
    public boolean shared() {
        return ownerId == null;
    }

    /** Whether {@code agent} sees it: their own, or a shared one. */
    public boolean visibleTo(UUID agent) {
        return archivedAt == null && (ownerId == null || ownerId.equals(agent));
    }

    /**
     * Whether {@code agent} may change it: their own; a shared one only as a supervisor.
     *
     * @throws BusinessException {@code CHAT_CANNED_RESPONSE_NOT_FOUND} for another agent's own (it is not theirs to
     *                           know about); {@code FORBIDDEN} for a shared one changed by a non-supervisor
     */
    public void requireChangeableBy(Actor agent) {
        if (archivedAt != null || (ownerId != null && !ownerId.equals(agent.userId()))) {
            throw new BusinessException(ErrorCode.CHAT_CANNED_RESPONSE_NOT_FOUND);
        }
        if (ownerId == null && !agent.supervisor()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A shared reply is the chat supervisor's to change");
        }
    }

    public CannedResponse revise(String newTitle, String newBody, String newShortcut, String newCategory) {
        return new CannedResponse(id, ownerId, newTitle, newBody, newShortcut, newCategory, archivedAt, version);
    }

    public CannedResponse archive(Instant now) {
        return new CannedResponse(id, ownerId, title, body, shortcut, category, now, version);
    }

    /** The body with its variables filled from {@code values}; a known one without a value becomes empty. */
    public String render(Map<String, String> values) {
        Matcher m = VARIABLE.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = VARIABLES.contains(name) ? Objects.toString(values.get(name), "") : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().strip();
    }

    private static String required(String value, int max, String field) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A saved reply has a " + field);
        }
        if (text.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A saved reply's " + field + " is at most " + max
                    + " characters");
        }
        return text;
    }
}

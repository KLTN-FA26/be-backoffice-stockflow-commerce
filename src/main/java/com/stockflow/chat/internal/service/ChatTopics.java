package com.stockflow.chat.internal.service;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat's STOMP destinations - one place, read by the publisher ({@link ChatBroadcaster}) and by the subscription
 * guard alike, so the two cannot drift apart.
 *
 * <ul>
 *   <li>{@code /topic/chat.conversations.{id}} - what both sides of a conversation see: messages to the customer,
 *       read markers, typing, the conversation's own changes. The customer of the conversation and the staff.</li>
 *   <li>{@code /topic/chat.conversations.{id}.staff} - what only the staff see: internal notes, staff-only system lines
 *       (16 BR-07). Never a customer. A staff client subscribes to both.</li>
 *   <li>{@code /topic/chat.inbox} - the queue moving: conversations opened, assigned, changing status. Staff.</li>
 *   <li>{@code /topic/chat.presence} - agents coming online and going away. Anyone signed in (the portal shows whether
 *       Sales is online, 16 §4.1 step 3).</li>
 *   <li>{@code /user/queue/chat} - one person's own: a conversation given to them, a note that mentions them, and for a
 *       customer every message of their conversations (a badge without subscribing to each).</li>
 *   <li>{@code /app/chat.conversations.{id}.typing}, {@code /app/chat.presence.heartbeat} - what a client sends.</li>
 * </ul>
 */
public final class ChatTopics {

    public static final String CONVERSATION_PREFIX = "/topic/chat.conversations.";
    public static final String STAFF_SUFFIX = ".staff";
    public static final String INBOX = "/topic/chat.inbox";
    public static final String PRESENCE = "/topic/chat.presence";
    public static final String USER_QUEUE = "/queue/chat";
    public static final String APP_PREFIX = "/app/chat.";
    public static final String TYPING_SUFFIX = ".typing";
    public static final String HEARTBEAT = "/app/chat.presence.heartbeat";

    private static final Pattern CONVERSATION = Pattern.compile(
            "^/(topic|app)/chat\\.conversations\\.([0-9a-fA-F-]{36})(\\.staff|\\.typing)?$");

    private ChatTopics() {
    }

    public static String conversation(UUID conversationId) {
        return CONVERSATION_PREFIX + conversationId;
    }

    public static String staff(UUID conversationId) {
        return CONVERSATION_PREFIX + conversationId + STAFF_SUFFIX;
    }

    /** A destination of one conversation, taken apart; empty for any other destination or a malformed id. */
    public static Optional<ConversationDestination> parse(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher m = CONVERSATION.matcher(destination);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(m.group(2));
            String suffix = m.group(3) == null ? "" : m.group(3);
            return Optional.of(new ConversationDestination(id, "app".equals(m.group(1)), STAFF_SUFFIX.equals(suffix),
                    TYPING_SUFFIX.equals(suffix)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /**
     * @param application a destination the client sends to ({@code /app}), not one it subscribes to
     * @param staffOnly   the conversation's staff topic
     * @param typing      the typing destination
     */
    public record ConversationDestination(UUID conversationId, boolean application, boolean staffOnly,
                                          boolean typing) {
    }
}

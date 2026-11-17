package com.stockflow.chat.internal.service;

import java.time.Instant;
import java.util.UUID;

/**
 * What the socket carries: one envelope for every event, so a client switches on {@link #type} alone.
 *
 * @param type           {@code message.created}, {@code message.updated}, {@code message.read}, {@code typing},
 *                       {@code conversation.updated}, {@code presence}
 * @param conversationId the conversation it is about; null for presence
 * @param data           the event's body: a {@link ChatMessageView}, a {@link ChatReadMarkerView}, a
 *                       {@link ChatConversationView}, a {@link Typing} or a {@link Presence}
 */
public record ChatEvent(String type, UUID conversationId, Object data, Instant at) {

    public static final String MESSAGE_CREATED = "message.created";
    public static final String MESSAGE_UPDATED = "message.updated";
    public static final String MESSAGE_READ = "message.read";
    public static final String TYPING = "typing";
    public static final String CONVERSATION_UPDATED = "conversation.updated";
    public static final String PRESENCE = "presence";

    /** Someone is typing (or stopped). Ephemeral: never stored. */
    public record Typing(UUID userId, String side, boolean typing) {
    }

    /** An agent came online or went away. */
    public record Presence(UUID userId, boolean online) {
    }
}

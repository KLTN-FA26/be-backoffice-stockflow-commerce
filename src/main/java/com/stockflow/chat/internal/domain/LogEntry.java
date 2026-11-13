package com.stockflow.chat.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a conversation's log (kltn-docs 16 BR-01): what happened, who did it, on which context and message.
 * Appended, never changed ({@code tg_conversation_event_append_only}).
 *
 * @param detailJson a JSON object with whatever else the action needs remembering (a card's action and its outcome,
 *                   the previous agent of a transfer); null when nothing
 */
public record LogEntry(UUID id, UUID conversationId, ConversationEventType type, SenderType actorType, UUID actorId,
                       Instant at, ConversationStatus from, ConversationStatus to, UUID contextId, UUID messageId,
                       UUID targetUserId, String note, String detailJson) {

    public LogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(at, "at");
        if (actorType != SenderType.SYSTEM && actorId == null) {
            throw new IllegalArgumentException("A person's action names the person (16 BR-01)");
        }
        if (type == ConversationEventType.STATUS_CHANGED && (from == null || to == null || from == to)) {
            throw new IllegalArgumentException("A status change says from what to what");
        }
        if (note != null && note.length() > 1000) {
            note = note.substring(0, 1000);
        }
    }

    /** A plain entry: no status change, no target. */
    public static LogEntry of(UUID id, UUID conversationId, ConversationEventType type, Actor actor, Instant at,
                              UUID contextId, UUID messageId) {
        return new LogEntry(id, conversationId, type, actor.type(), actor.userId(), at, null, null, contextId,
                messageId, null, null, null);
    }
}

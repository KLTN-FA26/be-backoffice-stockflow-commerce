package com.stockflow.chat.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One document a conversation is about. Written once and never changed ({@code tg_conversation_context_owner}): what
 * was said about a quote stays about that quote.
 *
 * @param primary    the context of the page the conversation was opened from; at most one per conversation
 * @param attachedBy the account that attached it; null when the system did
 */
public record ConversationContext(UUID id, UUID conversationId, ContextRef ref, boolean primary, UUID attachedBy,
                                  Instant attachedAt) {

    public ConversationContext {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(attachedAt, "attachedAt");
    }
}

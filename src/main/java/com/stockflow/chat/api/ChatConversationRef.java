package com.stockflow.chat.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A conversation as another module lists it on a document's page.
 *
 * @param status the chat's status (16 §5), e.g. {@code ACTIVE}, {@code RESOLVED}
 */
public record ChatConversationRef(UUID conversationId, UUID customerId, String subject, String status,
                                  UUID assignedAgentId, Instant createdAt, Instant lastMessageAt) {
}

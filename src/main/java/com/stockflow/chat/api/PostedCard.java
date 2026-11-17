package com.stockflow.chat.api;

import java.util.UUID;

/** The card put into a conversation: where, and its message (for {@link UpdateCardCommand} later). */
public record PostedCard(UUID conversationId, UUID messageId, long seq) {
}

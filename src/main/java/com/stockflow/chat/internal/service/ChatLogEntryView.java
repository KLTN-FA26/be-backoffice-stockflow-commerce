package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.chat.internal.domain.ConversationEventType;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.SenderType;

import java.time.Instant;
import java.util.UUID;

/** One line of a conversation's log (16 BR-01), for the staff's timeline. */
public record ChatLogEntryView(UUID id, ConversationEventType type, SenderType actorType, UUID actorId,
                               String actorName, Instant at, ConversationStatus from, ConversationStatus to,
                               UUID contextId, UUID messageId, UUID targetUserId, String targetName, String note,
                               JsonNode detail) {
}

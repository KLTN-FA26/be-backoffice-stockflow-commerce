package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.MessageKind;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.chat.internal.domain.Visibility;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A message as both REST and the socket show it. The chat's responses are the service's views, not DTOs of the
 * controller layer, because the same shapes are published over STOMP from the service layer; one shape for both is what
 * lets a client merge what it fetched with what it was pushed.
 *
 * @param seq       the message's place in the conversation; the cursor of {@code before} / {@code since}
 * @param body      null for a deleted message, and for a card or a file sent without text
 * @param mentions  the staff a note mentions; empty otherwise (and a customer never sees a note)
 */
public record ChatMessageView(UUID id, UUID conversationId, long seq, MessageKind kind, Visibility visibility,
                              SenderType senderType, UUID senderId, String senderName, String body,
                              String clientMessageId, UUID contextId, Card card, List<Attachment> attachments,
                              Set<UUID> mentions, Instant sentAt, Instant editedAt, boolean deleted) {

    /** A card (package 3): its kind, its state and its data, as the card's kind shapes it. */
    public record Card(CardKind kind, CardState state, JsonNode payload, Instant updatedAt) {
    }

    /** A file of the message; its link is asked for separately (a short-lived signed URL). */
    public record Attachment(UUID id, String fileName, String contentType, long sizeBytes, Integer width,
                             Integer height) {
    }
}

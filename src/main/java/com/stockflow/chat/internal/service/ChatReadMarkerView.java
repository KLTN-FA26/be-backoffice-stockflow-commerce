package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.SenderType;

import java.time.Instant;
import java.util.UUID;

/**
 * How far one participant has read a conversation: the {@code seq} of the last message they saw.
 *
 * @param side {@code CUSTOMER} or {@code AGENT}
 */
public record ChatReadMarkerView(UUID conversationId, UUID userId, SenderType side, long seq, Instant at) {
}

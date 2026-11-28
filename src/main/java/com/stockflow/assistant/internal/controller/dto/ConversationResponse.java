package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        @Schema(description = "The first question, cut to 120 characters; null until one is asked")
        String title,
        int messageCount,
        Instant lastActivityAt,
        Instant createdAt,
        @Schema(description = "CHAT when its owner opened it; PROACTIVE when the assistant's watch opened it for them, "
                + "with a question it asked about something new on their lists", example = "CHAT")
        String origin) {
}

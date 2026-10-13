package com.stockflow.notification.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One inbox message. {@code read} is {@code readAt != null}, for a client that only wants the flag. */
public record UserNotificationResponse(
        UUID id,
        @Schema(description = "A code of the module that posted it, e.g. INVENTORY_ALERT; group and pick icons by it")
        String category,
        @Schema(description = "INFO, WARN or CRITICAL")
        String severity,
        String title,
        String body,
        @Schema(description = "A route of the application to open, starting with '/'; null when there is none")
        String link,
        @Schema(description = "What the message is about, e.g. STOCK_ALERT; null when there is none")
        String sourceType,
        String sourceId,
        Instant createdAt,
        Instant readAt,
        boolean read) {
}

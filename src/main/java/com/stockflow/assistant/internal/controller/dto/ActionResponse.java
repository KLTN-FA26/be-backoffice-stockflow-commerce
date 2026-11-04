package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A suggestion of the assistant and what its owner decided (ADR-0011).
 *
 * @param suggested what was suggested: a proposal of {@code proposeReorder}, or a delivery note's draft receipt
 * @param submitted what was confirmed, the owner's edits included; null until CONFIRMED
 * @param resultId  the purchase order or goods receipt created, once CONFIRMED
 * @param possibleDuplicate only on the response that created a purchase order: the purchase-order screen's warning
 *                  that a similar order of the supplier and warehouse exists
 */
public record ActionResponse(
        UUID id,
        @Schema(example = "PURCHASE_ORDER_DRAFT") String kind,
        @Schema(example = "SUGGESTED",
                description = "SUGGESTED, CONFIRMED, DISMISSED, SUPERSEDED (a newer one replaced it), EXPIRED")
        String status,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        UUID resultId,
        @Schema(example = "PO-20261104-0001") String resultNumber,
        JsonNode suggested,
        JsonNode submitted,
        Boolean possibleDuplicate) {
}

package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A purchase order the assistant proposes (design §8), one per supplier. Nothing is created by the answer: the user
 * confirms it, edited or not, with {@code POST /api/v1/assistant/actions/{actionId}/purchase-order} under the
 * purchase-order screen's own permission (ADR-0011), or opens the create-PO form pre-filled with it.
 *
 * @param generatedAt  when the quantities were computed. A proposal reopened from an old conversation is history: the
 *                     panel shows its age and does not offer it as current
 * @param actionId     the suggestion to confirm or dismiss; null for an answer stored before suggestions were kept
 * @param actionStatus SUGGESTED, CONFIRMED, DISMISSED or EXPIRED; null with {@code actionId}
 * @param resultNumber the purchase order it became, once CONFIRMED
 */
public record ProposalResponse(
        @Schema(example = "PURCHASE_ORDER_DRAFT") String type,
        @Schema(example = "SUP-TANPHAT") String supplierCode,
        String supplierName,
        Instant generatedAt,
        List<Line> lines,
        UUID actionId,
        @Schema(example = "SUGGESTED") String actionStatus,
        @Schema(example = "PO-20261104-0001") String resultNumber) {

    public record Line(String sku, long quantity) {
    }
}

package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A purchase order the assistant proposes (design §8), one per supplier. Nothing is created: the panel opens the
 * existing create-PO form pre-filled with it, and the user edits and submits through that form.
 *
 * @param generatedAt when the quantities were computed. A proposal reopened from an old conversation is history: the
 *                    panel shows its age and does not offer it as current
 */
public record ProposalResponse(
        @Schema(example = "PURCHASE_ORDER_DRAFT") String type,
        @Schema(example = "SUP-TANPHAT") String supplierCode,
        String supplierName,
        Instant generatedAt,
        List<Line> lines) {

    public record Line(String sku, long quantity) {
    }
}

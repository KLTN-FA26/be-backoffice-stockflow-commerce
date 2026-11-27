package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The Production Coordinator's follow-ups (contract 3.3): production orders to chase, the most urgent first. */
public record ProductionFollowUpsResponse(
        @Schema(description = "The business day the rules were read on") LocalDate today,
        List<Item> items,
        @Schema(description = "Every production order to chase") long total,
        @Schema(description = "More than are listed; the rest are on the production orders screen")
        boolean truncated) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(
            UUID productionOrderId, String code, String producer, String status,
            @Schema(allowableValues = {"LATE", "PRODUCER_OVERDUE", "AT_RISK", "DUE_SOON"}) String reason,
            LocalDate expectedCompletion, LocalDate neededBy,
            @Schema(description = "Days past the need-by day (LATE) or the producer's day (PRODUCER_OVERDUE)")
            Integer daysLate,
            int cartons, int receivedCartons,
            @Schema(description = "The delivery batch it holds up: one production order per batch")
            List<Affected> affects,
            @Schema(allowableValues = {"HIGH", "MEDIUM", "LOW"}) String priority,
            String href) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Affected(
            @Schema(description = "Null for a caller who may not read orders") String orderNumber,
            Integer batchSeq,
            @Schema(description = "Null for a caller who may not read orders") String customerName,
            LocalDate windowStart, LocalDate windowEnd) {
    }

    /** {@code POST .../message-draft}: the draft to the producer, the contract's one {@code Draft}. */
    public record MessageDraft(DraftResponse draft) {
    }
}

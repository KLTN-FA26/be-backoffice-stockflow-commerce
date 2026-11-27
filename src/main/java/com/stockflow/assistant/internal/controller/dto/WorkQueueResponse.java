package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** The work queue (contract 3.1): what waits on the caller, section by section, worked out by code. */
public record WorkQueueResponse(
        @Schema(description = "When it was read") Instant generatedAt,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "The warehouse it was narrowed to, by its prefix; null for every warehouse the caller "
                + "sees", example = "HCM") String warehouse,
        @Schema(description = "Only the sections whose screen the caller may read, in a fixed order; narrowed to a "
                + "warehouse, only the sections that belong to one") List<Section> sections) {

    public record Section(
            @Schema(allowableValues = {"heldOrders", "awaitingPayment", "awaitingStock", "batchesDueToday",
                    "receiptsToPutAway", "pendingAdjustments", "bankTransactionsToReview", "overdueInstallments",
                    "creditReviewsPending", "quotesAwaitingPriceApproval", "productionOrdersLate",
                    "einvoicesToIssue"}) String key,
            @Schema(description = "In the caller's language") String title,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "Every item waiting; null when failed") Long count,
            @Schema(allowableValues = {"INFO", "WARN", "CRITICAL"}) String severity,
            @Schema(description = "The back-office screen listing them all") String href,
            @Schema(description = "The first five, each linking to its own screen") List<Item> items,
            @Schema(description = "The section could not be read; the error is logged and the others are not "
                    + "affected") boolean failed) {
    }

    public record Item(
            @Schema(description = "A code: an order number, a receipt number...") String label,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "Codes, figures and a customer name joined by ' · '; never text someone typed")
            String detail,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant at,
            String href) {
    }
}

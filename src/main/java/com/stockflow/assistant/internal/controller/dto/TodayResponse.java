package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** The "today" card: what wants attention in the caller's work, read now under their own permissions. */
public record TodayResponse(
        @Schema(description = "When the card was read") Instant generatedAt,
        @Schema(description = "One per thing the caller's tools can look at, in a fixed order; a section whose tool "
                + "the caller may not use is left out")
        List<Section> sections) {

    /** One part of the card, read by one assistant tool. */
    public record Section(
            @Schema(description = "What the section lists", allowableValues = {"lowStock", "expiredLots",
                    "expiringLots", "heldOrders", "awaitingPayment", "overduePurchaseOrders", "overdueProduction"})
            String key,
            @Schema(description = "The assistant tool that read it", example = "findOrders") String tool,
            @Schema(description = "How the tool's run ended. Only OK has a table; DENIED or FAILED has none and the "
                    + "note says why, while the other sections still answer",
                    allowableValues = {"OK", "DENIED", "NOT_FOUND", "INVALID", "FAILED"})
            String outcome,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "The rows, as an answer's tables with the section's key: each row has every column, "
                    + "null where the tool has no value; times are Vietnam time with their offset, dates YYYY-MM-DD, "
                    + "amounts as written. Text people typed (a hold note, with phone numbers, e-mail addresses and "
                    + "tax codes already hidden) is shown as plain text, never as HTML or Markdown. Null unless the "
                    + "outcome is OK", nullable = true)
            TableResponse table,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "What the reader needs to read the section right, in the caller's language: the "
                    + "expiry quantities are the nightly check's, an older late purchase order may be missing, or "
                    + "why there is no table", nullable = true)
            String note) {
    }
}

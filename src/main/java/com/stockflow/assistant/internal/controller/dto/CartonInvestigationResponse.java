package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** A missing carton, investigated (contract 3.2): facts of the carton screens side by side, and where to look. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CartonInvestigationResponse(
        String lpn, String sku,
        @Schema(allowableValues = {"RECEIVING", "IN_STOCK", "CONSUMED", "LOST", "VOID"}) String status,
        @Schema(description = "The stock's status while in stock; null otherwise") String stockStatus,
        @Schema(description = "Where the books last put it") String locationCode,
        String warehouseCode, String lotNumber, Instant receivedAt,
        @Schema(description = "Stock ledger lines, newest first, at most 30") List<TimelineEntry> timeline,
        @Schema(description = "Every ledger line of it the caller may see; null when the timeline is withheld")
        Long timelineTotal,
        @Schema(description = "Why the timeline is empty: BLIND_COUNT (a count holds the carton and the caller may "
                + "not see the books) or NO_PERMISSION (no inventory-stock-movements:READ); null when shown",
                allowableValues = {"BLIND_COUNT", "NO_PERMISSION"}) String timelineWithheld,
        List<OpenWork> openWork,
        @Schema(description = "In the order to look, each place once") List<Place> whereToLook) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TimelineEntry(Instant at, String movementType, String from, String to, String reference,
                                String by) {
    }

    public record OpenWork(
            @Schema(allowableValues = {"PICK_LINE", "CARTON_CHECK", "PUTAWAY_TASK", "COUNT_LINE", "ADJUSTMENT",
                    "RETURN_RECEIPT"}) String kind,
            String reference, String status, String href) {
    }

    public record Place(
            String locationCode,
            @Schema(allowableValues = {"LAST_RECORDED", "PUTAWAY_TARGET", "STAGING_AREA", "QUARANTINE",
                    "NEIGHBOUR_BIN", "RECEIVING_AREA"}) String why,
            @Schema(description = "Why, in the caller's language") String detail) {
    }
}

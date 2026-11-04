package com.stockflow.inventory.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Plan a cycle count (SCRUM-162). The scope's zones, shelves and bins are a union; SKUs narrow them, or alone select
 * every bin of the warehouse holding those SKUs. At least one criterion: an empty scope would count the whole
 * warehouse, which nobody plans by accident.
 */
@Schema(description = "Plan a cycle count over a scope of one warehouse")
public record PlanCycleCountRequest(

        @NotNull(message = "warehouseId is required")
        UUID warehouseId,

        @Schema(description = "Defaults to today", example = "2026-10-30")
        LocalDate plannedDate,

        @Schema(description = "An active warehouse staff member or manager of the warehouse; may be set later")
        UUID assignedUserId,

        @Schema(description = "Hide the book quantities from the counter; defaults to true")
        Boolean blind,

        @Size(max = 500, message = "note is at most 500 characters")
        String note,

        @NotNull(message = "scope is required")
        @Valid
        Scope scope
) {

    /** Blind unless asked otherwise: a counter who knows what to find finds it. */
    public boolean blindOrDefault() {
        return blind == null || blind;
    }

    @Schema(name = "CycleCountScope")
    public record Scope(
            @Size(max = 50, message = "zoneIds holds at most 50 zones")
            List<@NotNull UUID> zoneIds,

            @Schema(description = "A shelf's own code", example = "[\"A01\"]")
            @Size(max = 200, message = "shelfCodes holds at most 200 shelves")
            List<@NotBlank @Size(max = 20) String> shelfCodes,

            @Schema(description = "Full bin codes; case does not matter", example = "[\"HCM-A01-1-B\"]")
            @Size(max = 500, message = "locationCodes holds at most 500 codes")
            List<@NotBlank @Size(max = 64) String> locationCodes,

            @Size(max = 200, message = "skus holds at most 200 SKUs")
            List<@NotBlank @Size(max = 64) String> skus
    ) {

        @JsonIgnore
        @AssertTrue(message = "Name at least one zone, shelf, bin or SKU")
        public boolean isAnyCriterion() {
            return !empty(zoneIds) || !empty(shelfCodes) || !empty(locationCodes) || !empty(skus);
        }

        private static boolean empty(List<?> values) {
            return values == null || values.isEmpty();
        }
    }
}

package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What the assistant can look up for the caller: the panel offers what may be asked and says what is missing. */
public record CapabilitiesResponse(
        @Schema(description = "The caller's data scope", example = "WAREHOUSE") String scope,
        @Schema(description = "Codes of the warehouses the caller is assigned to, sorted") List<String> warehouses,
        @Schema(description = "Questions one user may ask per business day, Vietnam time", example = "50")
        int dailyLimit,
        @Schema(description = "Questions the caller has asked today", example = "3") long askedToday,
        @Schema(description = "Every tool, by name") List<ToolCapability> tools) {

    /** One tool as the caller stands with it. */
    public record ToolCapability(
            @Schema(example = "getStockLevels") String name,
            @Schema(description = "The caller may use it: they hold everything it requires and, for company-wide "
                    + "figures, are not limited to particular warehouses")
            boolean granted,
            @Schema(description = "Permissions as resource:ACTION, the tool's own guard first; an entry of "
                    + "alternatives joined by | is met by holding any one of them")
            List<String> requires,
            @Schema(description = "The entries of requires the caller lacks; empty on a tool refused only because "
                    + "its figures are company-wide")
            List<String> missing,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "COMPANY_WIDE_ONLY: company-wide figures, refused to a user limited to particular "
                    + "warehouses. PARTIAL: such a user gets only their own warehouses' rows, possibly none. Null: the "
                    + "same for everyone.", allowableValues = {"COMPANY_WIDE_ONLY", "PARTIAL"}, nullable = true)
            String limitation) {
    }
}

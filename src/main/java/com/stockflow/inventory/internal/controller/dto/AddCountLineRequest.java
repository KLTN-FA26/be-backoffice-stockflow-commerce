package com.stockflow.inventory.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A slot found on the shelf that the count did not list: a carton by its LPN, or loose stock by SKU and lot - always
 * with the bin it was found in. With an LPN the carton says what it holds, so a SKU or lot sent beside it could only
 * disagree; the bin is required because a carton found away from its recorded bin is a move, not a count (spec D6).
 */
@Schema(description = "Add a found line: locationCode (where it was found) with lpn, or with sku and the lot")
public record AddCountLineRequest(

        @Schema(example = "HCM-A01-1-B")
        @Size(max = 64, message = "locationCode is at most 64 characters")
        String locationCode,

        @Schema(example = "CUP-PP-500")
        @Size(max = 64, message = "sku is at most 64 characters")
        String sku,

        @Size(max = 64, message = "lotNumber is at most 64 characters")
        String lotNumber,

        @Schema(example = "LPN-000123")
        @Size(max = 30, message = "lpn is at most 30 characters")
        String lpn,

        @NotNull(message = "countedQty is required")
        @Min(value = 0, message = "countedQty must not be negative")
        @Max(value = 1_000_000, message = "countedQty is at most 1,000,000")
        Integer countedQty
) {

    @JsonIgnore
    @AssertTrue(message = "Send locationCode with either lpn or sku")
    public boolean isLpnOrLooseSlot() {
        return given(locationCode) && (given(lpn) ? !given(sku) && !given(lotNumber) : given(sku));
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }
}

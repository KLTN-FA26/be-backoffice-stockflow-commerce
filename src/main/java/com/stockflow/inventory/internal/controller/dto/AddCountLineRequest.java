package com.stockflow.inventory.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A slot found on the shelf that the count did not list: a carton by its LPN, or loose stock by bin, SKU and lot.
 * With an LPN the carton says what it holds, so a SKU or lot sent beside it could only disagree; the bin may be sent,
 * as where it was scanned, and must then be where the carton is recorded.
 */
@Schema(description = "Add a found line: lpn (optionally with the locationCode it was scanned in), or locationCode "
        + "and sku with the lot")
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
    @AssertTrue(message = "Send lpn, or locationCode and sku - not both")
    public boolean isLpnOrLooseSlot() {
        return given(lpn) ? !given(sku) && !given(lotNumber) : given(locationCode) && given(sku);
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }
}

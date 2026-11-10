package com.stockflow.inventory.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stockflow.common.validation.ValidSku;
import com.stockflow.inventory.api.StockAdjustmentReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A stock correction: loose stock by location, SKU and lot, or a whole carton by its LPN (team plan 4D-1: a
 * quarantined carton that cannot be used is written off as DAMAGED, kltn-docs 20 §4.8 step 3, BR-21).
 */
@Schema(description = "Ask for one stock item, or one whole carton (lpn), to be corrected; a different person "
        + "approves it")
public record RequestAdjustmentRequest(

        @Schema(example = "HCM-A01-1-A", description = "Required for loose stock; with an lpn optional, and then it "
                + "must be the carton's location")
        @Size(max = 64, message = "locationCode is at most 64 characters")
        String locationCode,

        @Schema(example = "CUP-PP-500", description = "Required for loose stock; with an lpn optional, and then it "
                + "must be the carton's SKU")
        @ValidSku
        String sku,

        @Size(max = 64, message = "lotNumber is at most 64 characters")
        String lotNumber,

        @Schema(description = "Negative to write off, positive to write up; never zero. For an lpn, minus what the "
                + "carton holds: a carton is written off whole (409 STOCK_PACKAGE_TAKEN_WHOLE otherwise)",
                example = "-3")
        @Min(value = -1_000_000, message = "quantityDelta is at least -1,000,000")
        @Max(value = 1_000_000, message = "quantityDelta is at most 1,000,000")
        int quantityDelta,

        @Schema(description = "Why the stock changed. COUNT_VARIANCE is refused here (400 VALIDATION_FAILED): it is "
                + "posted only by a cycle count's submission, which names the count line it comes from")
        @NotNull(message = "reason is required")
        StockAdjustmentReason reason,

        @Schema(description = "Required when reason is OTHER")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note,

        @Schema(example = "LPN-20261026-000001", description = "A carton to write off whole, in either case; omit for "
                + "loose stock")
        @Size(max = 30, message = "lpn is at most 30 characters")
        String lpn
) {

    @JsonIgnore
    @AssertTrue(message = "Send an lpn, or a locationCode and sku")
    public boolean isCartonOrLooseStock() {
        return given(lpn) || (given(locationCode) && given(sku));
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }
}

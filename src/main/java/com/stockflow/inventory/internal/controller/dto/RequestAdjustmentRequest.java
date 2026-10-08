package com.stockflow.inventory.internal.controller.dto;

import com.stockflow.inventory.api.StockAdjustmentReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Ask for one stock item to be corrected; a different person approves it")
public record RequestAdjustmentRequest(

        @Schema(example = "HCM-A01-1-A")
        @NotBlank(message = "locationCode is required")
        @Size(max = 64, message = "locationCode is at most 64 characters")
        String locationCode,

        @Schema(example = "CUP-PP-500")
        @NotBlank(message = "sku is required")
        @Pattern(regexp = "[A-Za-z0-9\\-]{3,64}", message = "malformed SKU")
        String sku,

        @Size(max = 64, message = "lotNumber is at most 64 characters")
        String lotNumber,

        @Schema(description = "Negative to write off, positive to write up; never zero", example = "-3")
        @Min(value = -1_000_000, message = "quantityDelta is at least -1,000,000")
        @Max(value = 1_000_000, message = "quantityDelta is at most 1,000,000")
        int quantityDelta,

        @NotNull(message = "reason is required")
        StockAdjustmentReason reason,

        @Schema(description = "Required when reason is OTHER")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

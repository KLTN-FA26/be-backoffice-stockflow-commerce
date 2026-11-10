package com.stockflow.inventory.internal.controller.dto;

import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ask for one LPN carton to be written off whole or restocked whole (kltn-docs 20 §4.3, BR-04, BR-14; team plan 4C-3).
 * No quantity: a carton is adjusted whole, so the system takes its standard count.
 */
@Schema(description = "Ask for one carton to be written off or restocked whole; a different person approves it")
public record RequestAdjustmentRequest(

        @Schema(example = "LPN-20261110-000001")
        @NotBlank(message = "lpn is required")
        @Size(max = 30, message = "lpn is at most 30 characters")
        String lpn,

        @Schema(description = "WRITE_OFF a carton in stock, or RESTOCK a LOST carton found again")
        @NotNull(message = "direction is required")
        AdjustmentDirection direction,

        @Schema(description = "DAMAGED, LOST, FOUND, DATA_CORRECTION or OTHER. COUNT_VARIANCE is refused here (400 "
                + "VALIDATION_FAILED): only a cycle count's submission asks for it")
        @NotNull(message = "reason is required")
        StockAdjustmentReason reason,

        @Schema(description = "Required when reason is OTHER")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

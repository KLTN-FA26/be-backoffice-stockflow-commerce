package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PUT replaces the logistics of one inventory item. {@code version} is the item's, as the last read returned it.
 * The item has no weight or size to send: those are the carton's, on its carton unit (kltn-docs 01 BR-03); a client
 * still sending the old measure fields has them ignored. Whether a carton may be turned is set on the catalogue
 * variant (owner decision Q9); a client still sending {@code rotatable} here has it ignored.

 */
public record UpdateItemLogisticsRequest(
        @NotNull(message = "version is required") @Min(0)
        Long version,

        @Schema(example = "EACH")
        @NotNull(message = "unitOfMeasure is required")
        @Pattern(regexp = "[A-Z0-9_]{1,16}", message = "unitOfMeasure is 1-16 upper-case letters, digits or _")
        String unitOfMeasure,

        @Size(min = 1, max = 64, message = "barcode must be 1-64 characters")
        String barcode
) {
}

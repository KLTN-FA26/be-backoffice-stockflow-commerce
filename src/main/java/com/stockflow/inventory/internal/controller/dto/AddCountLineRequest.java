package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A carton found on the shelf that the count did not list (kltn-docs 20 §4.2 b5): its LPN, scanned, and the bin it was
 * found in. The carton says what it holds, so no SKU, lot or quantity is sent beside it; the bin is required because
 * a carton found away from its recorded bin is a move, not a count.
 */
@Schema(description = "Add a found carton: the bin it was found in and its LPN")
public record AddCountLineRequest(

        @Schema(example = "HCM-A01-1-B")
        @NotBlank(message = "locationCode is required")
        @Size(max = 64, message = "locationCode is at most 64 characters")
        String locationCode,

        @Schema(example = "LPN-20261110-000001")
        @NotBlank(message = "lpn is required")
        @Size(max = 30, message = "lpn is at most 30 characters")
        String lpn
) {
}

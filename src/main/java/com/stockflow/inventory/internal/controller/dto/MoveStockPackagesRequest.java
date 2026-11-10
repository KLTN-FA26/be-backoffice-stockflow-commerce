package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Cartons scanned by LPN, and the one place they all go (kltn-docs 11 BR-04, BR-07). No SKU, lot or quantity: a carton
 * is moved whole, and its LPN says everything else.
 */
@Schema(description = "Move whole cartons, scanned by LPN, to one location; all of them or none")
public record MoveStockPackagesRequest(

        @Schema(description = "Idempotency key: resending the same value answers the move already made")
        @NotNull(message = "requestId is required")
        UUID requestId,

        @Schema(example = "[\"LPN-20261109-000001\"]")
        @NotEmpty(message = "lpns needs at least one carton")
        @Size(max = 500, message = "lpns holds at most 500 cartons")
        List<@NotBlank(message = "an LPN is not blank") @Size(max = 30, message = "an LPN is at most 30 characters")
                String> lpns,

        @Schema(example = "HCM-A01-1-A")
        @NotBlank(message = "toLocation is required")
        @Size(max = 64, message = "toLocation is at most 64 characters")
        String toLocation
) {
}

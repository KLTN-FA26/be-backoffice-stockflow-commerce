package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Move unreserved stock of one SKU and lot between two locations")
public record MoveStockRequest(

        @Schema(description = "Idempotency key: resending the same value returns the move already made")
        @NotNull(message = "requestId is required")
        UUID requestId,

        @Schema(example = "CUP-PP-500")
        @NotBlank(message = "sku is required")
        @Pattern(regexp = "[A-Za-z0-9\\-]{3,64}", message = "malformed SKU")
        String sku,

        @Size(max = 64, message = "lotNumber is at most 64 characters")
        String lotNumber,

        @Schema(example = "HCM-A01-1-A")
        @NotBlank(message = "fromLocation is required")
        @Size(max = 64, message = "fromLocation is at most 64 characters")
        String fromLocation,

        @Schema(example = "HCM-PACK01")
        @NotBlank(message = "toLocation is required")
        @Size(max = 64, message = "toLocation is at most 64 characters")
        String toLocation,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1_000_000, message = "quantity is at most 1,000,000")
        int quantity
) {
}

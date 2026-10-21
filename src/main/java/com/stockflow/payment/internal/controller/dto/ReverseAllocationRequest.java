package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Take an allocation back (kltn-docs 15 BR-06: a reversal row, nothing edited). {@code holdOrder} is the accountant's
 * agreement to put the order ON_HOLD when the money taken back leaves a confirmed order short of its terms; without it
 * that reversal is refused (409 {@code PAYMENT_REVERSAL_NEEDS_HOLD}).
 */
@Schema(description = "Reverse an allocation")
public record ReverseAllocationRequest(
        @Schema(example = "Allocated to the wrong order; the statement line names SO-20261021-000123")
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason,

        @Schema(description = "Agree to put a confirmed order left unpaid on hold", example = "false")
        boolean holdOrder
) {
}

package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Part of a transfer paid to one order (G2-3). */
@Schema(description = "Money of the transfer paid to one order")
public record OrderAllocationRequest(
        @NotNull(message = "orderId is required") UUID orderId,

        @Schema(example = "15000000")
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be above 0")
        BigDecimal amount
) {
}

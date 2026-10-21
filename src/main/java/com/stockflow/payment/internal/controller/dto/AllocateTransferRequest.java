package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pay part of a transfer's credit to an order or to a receivable (G2-3): exactly one of {@code orderId} and
 * {@code receivableId}. An order takes exactly {@code amount}; a receivable takes {@code amount}, or when it is
 * omitted as much as it owes and the transfer holds.
 */
@Schema(description = "Allocate a transfer's credit to an order or a receivable")
public record AllocateTransferRequest(
        UUID orderId,
        UUID receivableId,
        @Schema(example = "10000000", description = "Required for an order")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be above 0")
        BigDecimal amount
) {
}

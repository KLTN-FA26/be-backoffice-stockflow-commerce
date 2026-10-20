package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.internal.service.CreditHoldService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Refuse an order over the credit limit (kltn-docs 15 §4.3): cancel it, or let the customer pay
 * another way they are allowed.
 */
public record CreditRefusalRequest(
        @Schema(example = "SWITCH_TO_DEPOSIT")
        @NotNull(message = "action is required")
        CreditHoldService.RefusalAction action,

        @Schema(description = "SWITCH_TO_DEPOSIT only; defaults to the customer's deposit share", example = "30")
        @DecimalMin(value = "0.0", inclusive = false, message = "depositPercent must be above 0")
        @DecimalMax(value = "100.0", inclusive = false, message = "depositPercent must be below 100")
        BigDecimal depositPercent,

        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

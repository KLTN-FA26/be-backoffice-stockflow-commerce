package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Decision Q2: the amount agreed with the customer for a job already printing, with why and what was agreed. */
@Schema(description = "Amount agreed with the customer, kept from what was paid; recording it charges nothing")
public record CancellationFeeRequest(
        @NotNull(message = "amount is required") @DecimalMin(value = "0", inclusive = false,
                message = "amount is more than zero") BigDecimal amount,
        @NotBlank(message = "reason is required") @Size(max = 500, message = "reason is at most 500 characters")
        String reason,
        @NotBlank(message = "detail is required") @Size(max = 2000, message = "detail is at most 2000 characters")
        String detail
) {
}

package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The Accountant decides a transfer is no installment's payment here, and says why (part C; 15 BR-06). */
@Schema(description = "Ignore a bank transfer, with the reason")
public record IgnoreBankTransactionRequest(
        @Schema(example = "Not a customer's payment: interest paid by the bank")
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}

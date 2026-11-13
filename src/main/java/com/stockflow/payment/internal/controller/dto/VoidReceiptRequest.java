package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Void a receipt recorded wrong (4B-6, kltn-docs 15 §4.4): why, and whether a confirmed order it leaves short of its
 * installment on signing may be put on hold - without that, such a void is refused (BR-13).
 */
@Schema(description = "Void a payment receipt")
public record VoidReceiptRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason,
        @Schema(description = "Put on hold, for payment, a confirmed order the void leaves short")
        boolean holdOrders
) {
}

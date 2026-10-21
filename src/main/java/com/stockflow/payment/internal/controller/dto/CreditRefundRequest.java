package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Refund part of a transfer's unallocated money to the customer (kltn-docs 15 edge "khách chuyển thừa ... hoàn lại",
 * "tiền về sau khi đơn đã huỷ"): a PENDING refund the accountant pays out; never more than the transfer still holds.
 */
@Schema(description = "Refund part of a customer's credit")
public record CreditRefundRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be above 0")
        BigDecimal amount,

        @NotBlank(message = "reason is required")
        @Size(max = 255, message = "reason is at most 255 characters")
        String reason
) {
}

package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Approve a cancellation request; the order is cancelled with it. */
public record ApproveCancellationRequest(

        @Schema(description = "Share of the money received kept for work already done, 0-100; default 0", example = "0")
        @DecimalMin(value = "0.0", message = "retainedPercent is at least 0")
        @DecimalMax(value = "100.0", message = "retainedPercent is at most 100")
        BigDecimal retainedPercent,

        @Size(max = 1000, message = "note is at most 1000 characters")
        String note,

        @Schema(description = "Instead of retainedPercent: an amount agreed with the customer (decision Q2)")
        @jakarta.validation.Valid
        CancellationFeeRequest agreedFee,

        @Schema(description = "Who bears the fee (D46, D50): the customer is charged it, or the company bears it - then the customer is "
                + "charged nothing and the amount is the company's cost. Required whenever a fee is given "
                + "(retainedPercent above 0, or agreedFee), no default; refused without a fee")
        com.stockflow.order.api.CancellationFeeBearer feeBearer
) {
}

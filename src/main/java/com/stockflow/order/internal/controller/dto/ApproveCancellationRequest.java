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
        String note
) {
}

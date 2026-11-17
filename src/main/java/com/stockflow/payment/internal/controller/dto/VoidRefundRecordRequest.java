package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Void a refund record made wrong (4B-9, kltn-docs 15 §4.5 step 16): why. What it recorded is to refund again. */
@Schema(description = "Void a refund record")
public record VoidRefundRecordRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}

package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 03 §16 G9 "gửi bù": units added to the SUBCONTRACT PO for a resend after a QC shortfall, with the reason. */
public record SubcontractSupplementRequest(
        @Min(value = 1, message = "quantity is at least 1") int quantity,
        @NotBlank(message = "reason is required") @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}

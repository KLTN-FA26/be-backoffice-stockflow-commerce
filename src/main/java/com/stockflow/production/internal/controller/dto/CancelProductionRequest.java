package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Why the job is cancelled. For a subcontracted job (03 §16 SUBCONTRACTED → CANCELLED), {@code subcontractorFee} is
 * an amount agreed with the subcontractor for work done after it was sent out, with its reason; it is added to the
 * subcontract settlement, never charged or paid by the system. Empty for none, and for a job printed in house.
 */
public record CancelProductionRequest(
        @NotBlank(message = "reason is required") @Size(max = 500, message = "reason is at most 500 characters")
        String reason,
        @DecimalMin(value = "0", message = "subcontractorFee cannot be negative")
        @Digits(integer = 16, fraction = 2, message = "subcontractorFee has at most 2 decimals")
        BigDecimal subcontractorFee,
        @Size(max = 1000, message = "subcontractorFeeReason is at most 1000 characters")
        String subcontractorFeeReason
) {
    /** A cancellation with no subcontractor fee. */
    public CancelProductionRequest(String reason) {
        this(reason, null, null);
    }
}

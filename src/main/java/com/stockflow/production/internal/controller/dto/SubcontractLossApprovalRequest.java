package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * BR-PRD-17: the warehouse manager approves blanks lost above the subcontractor's tolerance, with the deduction per
 * lost blank agreed with the subcontractor (the system has no price of its own for a blank).
 */
public record SubcontractLossApprovalRequest(
        @NotNull(message = "deductionPerBlank is required")
        @DecimalMin(value = "0", message = "deductionPerBlank cannot be negative")
        @Digits(integer = 16, fraction = 2, message = "deductionPerBlank has at most 2 decimals")
        BigDecimal deductionPerBlank,
        @Size(max = 1000, message = "note is at most 1000 characters") String note
) {
}

package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * The warehouse's reconciliation of one subcontract receipt (decision Q9): blanks spoilt at the subcontractor and
 * surplus blanks returned with the goods. {@code returnLocation}: where the returned blanks go; empty for where
 * each was sent from.
 */
public record SubcontractReconciliationRequest(
        @Min(value = 0, message = "spoilt cannot be negative") int spoilt,
        @Min(value = 0, message = "returned cannot be negative") int returned,
        @Size(max = 64, message = "returnLocation is at most 64 characters") String returnLocation
) {
}

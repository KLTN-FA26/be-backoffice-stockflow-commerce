package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Blanks brought back from the subcontractor without a goods receipt (BR-PRD-15): {@code returned} back to stock
 * ({@code returnLocation}, or empty for where each was sent from), {@code spoilt} written off as SUBCONTRACT_LOSS
 * through the adjustment approval. {@code requestId} makes a retry a replay.
 */
public record SubcontractRecallRequest(
        @NotNull(message = "requestId is required") UUID requestId,
        @Min(value = 0, message = "returned cannot be negative") int returned,
        @Min(value = 0, message = "spoilt cannot be negative") int spoilt,
        @Size(max = 64, message = "returnLocation is at most 64 characters") String returnLocation,
        @Size(max = 1000, message = "note is at most 1000 characters") String note
) {
}

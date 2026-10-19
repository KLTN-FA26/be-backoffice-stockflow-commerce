package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One scan: the hold to issue (or none, for the next one) to a PRODUCTION area, idempotent on requestId.
 * {@code scannedLocation} and {@code scannedCode} are what the clerk scanned where the blanks were taken (SCRUM-409);
 * each is checked against the hold when given, and may be left out by a client that does not scan the source.
 */
public record MaterialIssueRequest(
        @NotNull(message = "requestId is required") UUID requestId,
        @NotBlank(message = "toLocation is required") @Size(max = 64, message = "toLocation is at most 64 characters")
        String toLocation,
        UUID reservationId,
        @Size(max = 64, message = "scannedLocation is at most 64 characters") String scannedLocation,
        @Size(max = 128, message = "scannedCode is at most 128 characters") String scannedCode
) {
}

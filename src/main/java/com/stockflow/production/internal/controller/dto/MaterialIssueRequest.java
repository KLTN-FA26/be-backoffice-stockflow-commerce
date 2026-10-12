package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** One scan: the hold to issue (or none, for the next one) to a PRODUCTION area, idempotent on requestId. */
public record MaterialIssueRequest(
        @NotNull(message = "requestId is required") UUID requestId,
        @NotBlank(message = "toLocation is required") @Size(max = 64, message = "toLocation is at most 64 characters")
        String toLocation,
        UUID reservationId
) {
}

package com.stockflow.inventory.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What the clerk scanned: the bin, and the carton's LPN for a carton task or a count for a loose one. Which of the
 * two applies depends on the task, which only the service knows, so the pairing is checked there.
 */
public record PutawayConfirmationRequest(
        @NotBlank @Size(max = 64) String locationCode,
        @Size(max = 30) String lpn,
        @Positive Integer quantity) {
}

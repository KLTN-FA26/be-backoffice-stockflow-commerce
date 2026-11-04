package com.stockflow.inventory.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A reason for a rejection or a cancellation. */
public record ReasonRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}

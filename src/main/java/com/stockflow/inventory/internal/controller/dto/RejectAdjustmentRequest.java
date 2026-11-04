package com.stockflow.inventory.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectAdjustmentRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}

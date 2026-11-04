package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a draft round is called off. */
public record SampleCancellationRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}

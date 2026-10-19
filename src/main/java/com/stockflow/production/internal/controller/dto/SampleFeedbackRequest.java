package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What the customer wants changed. */
public record SampleFeedbackRequest(
        @NotBlank(message = "feedback is required")
        @Size(max = 2000, message = "feedback is at most 2000 characters")
        String feedback
) {
}

package com.stockflow.order.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reject a cancellation request: the customer is told why. */
public record RejectCancellationRequest(

        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

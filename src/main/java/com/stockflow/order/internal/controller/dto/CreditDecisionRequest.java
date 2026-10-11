package com.stockflow.order.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Approve an order over the credit limit: why (kltn-docs 15 BR-03). */
public record CreditDecisionRequest(
        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

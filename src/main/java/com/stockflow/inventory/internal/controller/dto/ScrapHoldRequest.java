package com.stockflow.inventory.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the cancelled order's printed goods are to be scrapped this time (the last write-off was rejected). */
public record ScrapHoldRequest(
        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

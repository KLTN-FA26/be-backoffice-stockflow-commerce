package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Refuse an order held for credit (kltn-docs 15 §4.3 step 10, BR-11): it is cancelled with reason
 * {@code CREDIT_REJECTED}. Since 4B-10 there is no other way out - the order pays as its signed contract says, and there
 * are no prepaid or deposit terms to switch it to; an {@code action} or {@code depositPercent} a client still sends is
 * ignored.
 */
public record CreditRefusalRequest(
        @Schema(description = "Why credit was refused; recorded with the decision")
        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * A shipment's outcome corrected because the carrier reported it wrongly (kltn-docs 09 §4.3 step 5, E17).
 *
 * @param outcome       what the shipment really came to: DELIVERED or DELIVERY_FAILED
 * @param failureReason required with DELIVERY_FAILED: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED, OTHER
 * @param note          required with OTHER
 * @param reason        why the carrier's report was wrong; required, kept with the correction
 * @param deliveredAt   optional, only when correcting to DELIVERED (ISO-8601): when it was delivered; absent = now.
 *                      Not in the future, not before the shipment was handed over
 */
public record DeliveryOutcomeCorrectionRequest(
        @NotBlank @Pattern(regexp = "^(DELIVERED|DELIVERY_FAILED)$") String outcome,
        @Size(max = 24) String failureReason,
        @Size(max = 500) String note,
        @NotBlank @Size(max = 500) String reason,
        Instant deliveredAt) {

    /** Without a delivery time: the moment it is corrected. */
    public DeliveryOutcomeCorrectionRequest(String outcome, String failureReason, String note, String reason) {
        this(outcome, failureReason, note, reason, null);
    }
}

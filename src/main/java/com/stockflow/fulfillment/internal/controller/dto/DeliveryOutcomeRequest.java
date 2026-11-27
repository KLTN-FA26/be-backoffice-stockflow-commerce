package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * What the carrier reported for a shipment (kltn-docs 09 §4.3).
 *
 * @param outcome       DELIVERED, DELIVERY_FAILED or RETURNED
 * @param failureReason required with DELIVERY_FAILED: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED, OTHER
 * @param note          required with OTHER; staff only, never shown to the customer
 * @param deliveredAt   optional, only with DELIVERED (ISO-8601): when the carrier delivered it, which dates the order's
 *                      delivery and the shipment's draft invoice (09 §4.3 step 2); absent = now. Not in the future,
 *                      not before the shipment was handed over
 */
public record DeliveryOutcomeRequest(@NotBlank @Pattern(regexp = "^(DELIVERED|DELIVERY_FAILED|RETURNED)$") String outcome,
                                     @Size(max = 24) String failureReason,
                                     @Size(max = 500) String note,
                                     Instant deliveredAt) {

    /** Without a delivery time: the moment it is recorded. */
    public DeliveryOutcomeRequest(String outcome, String failureReason, String note) {
        this(outcome, failureReason, note, null);
    }
}

package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the carrier reported for a shipment (kltn-docs 09 §4.3).
 *
 * @param outcome       DELIVERED, DELIVERY_FAILED or RETURNED
 * @param failureReason required with DELIVERY_FAILED: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED, OTHER
 * @param note          required with OTHER; staff only, never shown to the customer
 */
public record DeliveryOutcomeRequest(@NotBlank @Pattern(regexp = "^(DELIVERED|DELIVERY_FAILED|RETURNED)$") String outcome,
                                     @Size(max = 24) String failureReason,
                                     @Size(max = 500) String note) {
}

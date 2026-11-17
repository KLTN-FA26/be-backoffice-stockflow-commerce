package com.stockflow.customer.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Set a customer's credit (kltn-docs 18 §3, 15 BR-12): the limit and the days to pay after delivery, both or neither -
 * checked by the domain and answered 400 {@code CREDIT_PROFILE_INVALID}. Since 4B-10 there are no payment terms to set:
 * a contract's installment plan says how the customer pays.
 */
@Schema(description = "A customer's credit")
public record SaveCreditProfileRequest(
        @Schema(example = "50000000", description = "0 or more; with creditTermDays, or neither (no credit)")
        BigDecimal creditLimit,
        @Schema(example = "30", description = "Days to pay after delivery, 0-365; with creditLimit, or neither")
        Integer creditTermDays,
        @Size(max = 1000, message = "note is at most 1000 characters") String note,
        @Schema(description = "The version read (GET returns -1 while no terms are set); required when the customer "
                + "already has terms, otherwise 400 VALIDATION_FAILED; a stale one is 409 OPTIMISTIC_LOCK") Long expectedVersion
) {
}

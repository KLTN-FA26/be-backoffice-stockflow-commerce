package com.stockflow.customer.internal.controller.dto;

import com.stockflow.customer.api.PaymentMilestone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * A customer's default payment schedule, replaced whole (kltn-docs 18 §3).
 *
 * @param installments    in order; the shares add up to 100
 * @param expectedVersion the version read; left out only for a customer that has none yet
 */
public record SavePaymentScheduleRequest(@NotEmpty @Size(max = 12) List<@Valid @NotNull Line> installments,
                                         @Size(max = 1000) String note,
                                         Long expectedVersion) {

    /**
     * @param percent share of the total with tax, two decimals at most
     * @param dueDays days after the milestone, 0 when left out
     */
    public record Line(@NotNull @Size(max = 100) String name, @NotNull BigDecimal percent,
                       @NotNull PaymentMilestone milestone, Integer dueDays) { }
}

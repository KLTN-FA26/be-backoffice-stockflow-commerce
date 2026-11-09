package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One installment of a contract's payment plan (kltn-docs 14 BR-13; 15 BR-18): an amount of the order's total with tax,
 * one milestone and, for BEFORE_DELIVERY / AFTER_DELIVERY, at most one delivery batch it is tied to. 4B-6 turns the plan
 * of a signed contract into installments to collect.
 *
 * @param percent          the share Sales entered, when entered as one; the amount is what counts
 * @param dueDays          days after the milestone it falls due; null for BEFORE_DELIVERY, which has no due day
 * @param deliveryBatchSeq the number of the delivery batch it is tied to; null when it is tied to none
 */
public record PlannedInstallment(UUID id, int seq, String name, PaymentMilestone milestone, BigDecimal percent,
                                 Money amount, Integer dueDays, Integer deliveryBatchSeq) {

    public PlannedInstallment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(milestone, "milestone");
        Objects.requireNonNull(amount, "amount");
    }
}

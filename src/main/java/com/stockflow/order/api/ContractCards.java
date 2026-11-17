package com.stockflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A sales contract as the chat's contract card shows it (kltn-docs 16 §4.3 step 8): its number, where it stands, the
 * order's total and the payment plan. Read-only: the contract is sent, signed and cancelled on its own page (14 §4.3).
 */
public interface ContractCards {

    /**
     * One installment of the plan.
     *
     * @param milestone ON_SIGNING, BEFORE_DELIVERY or AFTER_DELIVERY
     * @param dueDays   days to pay from the milestone; null for one paid before delivery
     */
    record Installment(int seq, String name, String milestone, BigDecimal amount, BigDecimal percent, Integer dueDays,
                       Integer deliveryBatchSeq) {
    }

    /**
     * @param status DRAFT, SENT, SIGNED, CANCELLED or COMPLETED, the order module's names
     */
    record Card(UUID id, String number, UUID orderId, String orderNumber, String status,
                BigDecimal totalAmount, String currency, int freeSampleRounds, List<Installment> paymentPlan,
                Instant sentAt, LocalDate signedOn, Instant cancelledAt, long version) {
    }

    /** The contract {@code contractId} of order {@code orderId}, cancelled ones included. */
    Optional<Card> contract(UUID orderId, UUID contractId);
}

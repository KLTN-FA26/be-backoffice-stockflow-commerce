package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A contract just signed and the plan its installments are made from (4B-6, kltn-docs 15 §4.1 step 1): what the order
 * module hands {@link PaymentService#openInstallments} inside the signing transaction.
 *
 * @param signedOn the day written on the contract: an installment due on signing is due by it plus its days to pay
 * @param signedAt when the signature was recorded: when the installment on signing fell due
 */
public record ContractInstallments(UUID contractId, String contractNumber, UUID orderId, String orderNumber,
                                   UUID customerId, String currency, LocalDate signedOn, Instant signedAt,
                                   List<Planned> installments) {

    public ContractInstallments {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(signedOn, "signedOn");
        Objects.requireNonNull(signedAt, "signedAt");
        installments = List.copyOf(installments);
    }

    /**
     * One row of the signed plan ({@code ordering.contract_payment_installment}).
     *
     * @param milestone        {@code ON_SIGNING}, {@code BEFORE_DELIVERY} or {@code AFTER_DELIVERY}
     * @param dueDays          days after the milestone it falls due; null for BEFORE_DELIVERY
     * @param deliveryBatchId  the delivery batch it is tied to (17 §4.5); null when none
     * @param deliveryBatchSeq that batch's place in the schedule; null when none
     */
    public record Planned(UUID contractInstallmentId, int seq, String name, String milestone, BigDecimal amount,
                          Integer dueDays, UUID deliveryBatchId, Integer deliveryBatchSeq) {
    }
}

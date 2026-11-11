package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the contracts' installments and the receipts that pay them (4B-6, kltn-docs 15 §4.1-§4.4). One
 * port for both: they change together - a receipt and the installments it pays are written in one transaction, and
 * {@code check_installment_paid} holds them to each other at commit.
 *
 * <p>Locks are taken receipts first, then installments in id order, everywhere, so two receipts paying the same
 * installments - or a receipt and its void - never deadlock.</p>
 */
public interface InstallmentBook {

    /** One receipt that paid an installment, for the installment's own view. */
    record ReceiptOfInstallment(UUID receiptId, String reference, LocalDate receivedOn, BigDecimal amount,
                                PaymentReceiptStatus status, Instant recordedAt) {
    }

    /** What the customer has overdue on installments: the unpaid part and the earliest due day; zero and null when none. */
    record Overdue(BigDecimal amount, LocalDate oldestDueDate) {
    }

    Installment save(Installment installment);

    Optional<Installment> find(UUID id);

    /** The order's installments, in the plan's order. */
    List<Installment> ofOrder(UUID orderId);

    /** The same, each row locked until the transaction ends, taken in id order. */
    List<Installment> lockOfOrder(UUID orderId);

    /** These installments, locked in id order and read fresh; an id with no installment is left out. */
    List<Installment> lock(Collection<UUID> ids);

    /** Whether the contract's installments were opened already (a signature replayed opens nothing twice). */
    boolean opened(UUID contractId);

    /** DUE and PARTIALLY_PAID installments past their due day become OVERDUE (15 §4.1 step 4); returns their ids. */
    List<UUID> markOverdue(LocalDate today);

    /**
     * 15 BR-10: what the customer's installments paid after delivery still owe - every status but PAID and CANCELLED,
     * NOT_DUE included: every signed contract's, held for credit or not - and, since 4B-9, the cancellation fee items not
     * paid ("khoản phí huỷ chưa trả"); each net of what closures of the rest took off.
     */
    BigDecimal afterDeliveryUnpaid(UUID customerId);

    /** The customer's OVERDUE installments, of any milestone (15 §4.3 step 9: "đợt / khoản quá hạn"). */
    Overdue overdue(UUID customerId);

    // ---- receipts ---------------------------------------------------------------------------------------------------

    /** Writes a new receipt and its lines. */
    PaymentReceipt record(PaymentReceipt receipt);

    /** Writes the void of a receipt read through {@link #lockReceipt}. */
    PaymentReceipt saveVoided(PaymentReceipt receipt);

    Optional<PaymentReceipt> findReceipt(UUID id);

    /** The receipt, its row locked until the transaction ends and read fresh. */
    Optional<PaymentReceipt> lockReceipt(UUID id);

    /**
     * 15 BR-03: whether a live receipt records this statement reference: one statement line is one sum of money,
     * recorded once.
     */
    boolean referenceInUse(String reference);

    /** The receipts with a line paying the installment, oldest first, voided ones included. */
    List<ReceiptOfInstallment> receiptsOf(UUID installmentId);
}

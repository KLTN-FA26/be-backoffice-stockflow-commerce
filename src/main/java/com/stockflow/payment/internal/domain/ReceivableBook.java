package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the money ledger (SCRUM-431, G2-3): receivables, transfers and allocations. One port for the
 * three: they change together - an allocation moves money from a transfer to an order or a receivable in one
 * transaction.
 */
public interface ReceivableBook {

    /**
     * One allocation, as recorded: immutable (15 BR-06). {@code receivableId} is null for money paid to the order
     * itself (prepaid, deposit, balance before delivery). A reversal has a negative amount and names the allocation it
     * reverses in {@code reversesId}, with the accountant's reason in {@code note}.
     */
    record Allocation(UUID id, UUID transferId, UUID orderId, UUID receivableId, BigDecimal amount,
                      Instant allocatedAt, UUID allocatedBy, UUID reversesId, String note) {

        public boolean isReversal() {
            return reversesId != null;
        }

        public boolean paysOrder() {
            return receivableId == null;
        }
    }

    /** A refund asked of an order's money, as recorded (the accountant pays it out, SCRUM-220). */
    record RefundLine(UUID id, UUID paymentId, UUID allocationId, BigDecimal amount, String currency, String status,
                      String source, Instant createdAt) {
    }

    Optional<Receivable> findReceivable(UUID id);

    /** The receivable locked for the rest of the transaction. */
    Optional<Receivable> lockReceivable(UUID id);

    /** The order's receivable not tied to a shipment: a whole-order delivery's, or a cancellation fee's. */
    Optional<Receivable> findReceivableOfOrder(UUID orderId);

    /** G3-2: the receivable of one delivered shipment of a split order. */
    Optional<Receivable> findReceivableOfShipment(UUID orderId, UUID shipmentId);

    /** The customer's unpaid receivables they pay themselves (never a carrier's), locked, earliest due first (BR-09). */
    List<Receivable> lockUnpaid(UUID customerId);

    Receivable save(Receivable receivable);

    boolean referenceRecorded(String reference);

    Optional<CustomerTransfer> findTransfer(UUID id);

    /** The transfer locked for the rest of the transaction. */
    Optional<CustomerTransfer> lockTransfer(UUID id);

    /** The customer's transfers with money not yet allocated, locked, oldest first. */
    List<CustomerTransfer> lockWithCredit(UUID customerId);

    CustomerTransfer save(CustomerTransfer transfer);

    /** @param receivableId null when the money pays the order itself */
    Allocation recordAllocation(UUID transferId, UUID orderId, UUID receivableId, BigDecimal amount, Instant at,
                                UUID by);

    /** A new row taking back what {@code original} brought (BR-06), at most once per allocation. */
    Allocation recordReversal(Allocation original, Instant at, UUID by, String note);

    Optional<Allocation> findAllocation(UUID id);

    List<Allocation> allocationsOfReceivable(UUID receivableId);

    List<Allocation> allocationsOfTransfer(UUID transferId);

    /** Every allocation and reversal that brought money to the order, directly or through its receivables. */
    List<Allocation> allocationsOfOrder(UUID orderId);

    boolean isReversed(UUID allocationId);

    /** Whether a refund (not FAILED) was asked of this allocation's money. */
    boolean hasRefunds(UUID allocationId);

    /**
     * What the order holds of the money received: net allocations (direct and through its receivables), plus captured
     * payments recorded before the ledger, minus refunds asked for (not FAILED). What a further allocation is measured
     * against.
     */
    BigDecimal collectedFor(UUID orderId);

    /** Refunds asked of the order's money, oldest first. */
    List<RefundLine> refundsOfOrder(UUID orderId);

    /** The order's receivables, earliest due first. */
    List<Receivable> receivablesOfOrder(UUID orderId);

    /** Net allocations that paid the order's receivables. */
    BigDecimal collectedOnReceivablesOf(UUID orderId);

    /** What the order's receivables still have outstanding (the customer's or the carrier's to pay). */
    BigDecimal outstandingReceivablesOf(UUID orderId);

    /**
     * A PENDING refund of a transfer's credit (source CUSTOMER_CREDIT) for the accountant to pay out.
     *
     * @return the refund id
     */
    UUID recordCreditRefund(CustomerTransfer transfer, BigDecimal amount, String reason);

    /** Marks every OPEN / PARTIALLY_PAID receivable due before {@code today} OVERDUE; returns their ids. */
    List<UUID> markOverdue(LocalDate today);

    /** What the customer owes on the receivables they pay themselves, and whether any is overdue. */
    BigDecimal outstanding(UUID customerId);

    boolean hasOverdue(UUID customerId);

    /** What is overdue on the receivables the customer pays themselves (SCRUM-193): the amount and the oldest due date. */
    Overdue overdue(UUID customerId);

    /** @param amount what is still unpaid on OVERDUE receivables; zero when none. @param oldestDueDate null when none */
    record Overdue(BigDecimal amount, LocalDate oldestDueDate) {
    }
}

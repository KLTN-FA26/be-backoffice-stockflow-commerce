package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for receivables, transfers and allocations (SCRUM-431). One port for the three: they
 * change together — an allocation moves money from a transfer to a receivable in one transaction.
 */
public interface ReceivableBook {

    /** One allocation, as recorded: immutable (15 BR-06). */
    record Allocation(UUID id, UUID transferId, UUID receivableId, BigDecimal amount, Instant allocatedAt,
                      UUID allocatedBy) {
    }

    Optional<Receivable> findReceivable(UUID id);

    Optional<Receivable> findReceivableOfOrder(UUID orderId);

    /** The customer's unpaid receivables, locked, earliest due first (15 BR-09). */
    List<Receivable> lockUnpaid(UUID customerId);

    Receivable save(Receivable receivable);

    boolean referenceRecorded(String reference);

    /** The customer's transfers with money not yet allocated, locked, oldest first. */
    List<CustomerTransfer> lockWithCredit(UUID customerId);

    CustomerTransfer save(CustomerTransfer transfer);

    Allocation recordAllocation(UUID transferId, UUID receivableId, BigDecimal amount, Instant at, UUID by);

    List<Allocation> allocationsOfReceivable(UUID receivableId);

    List<Allocation> allocationsOfTransfer(UUID transferId);

    /** Marks every OPEN / PARTIALLY_PAID receivable due before {@code today} OVERDUE; returns their ids. */
    List<UUID> markOverdue(LocalDate today);

    /** What the customer owes on receivables, and whether any is overdue. */
    BigDecimal outstanding(UUID customerId);

    boolean hasOverdue(UUID customerId);
}

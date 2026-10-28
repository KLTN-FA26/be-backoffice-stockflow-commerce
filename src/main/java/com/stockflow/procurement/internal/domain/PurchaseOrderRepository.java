package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRepository;
import com.stockflow.common.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port of the {@link PurchaseOrder} aggregate, plus its append-only history: revisions, the
 * approval decisions on them, and the event log ({@code purchase_order_revisions},
 * {@code purchase_order_approvals}, {@code purchase_order_events}). Those three are written once and
 * never changed (triggers {@code platform.append_only}), so they are recorded, not saved.
 */
public interface PurchaseOrderRepository extends AggregateRepository<PurchaseOrder, PurchaseOrderId> {

    /**
     * {@code nextPoNumber} sits here rather than in a utility class because generating the next
     * number is a persistence concern — it needs a database sequence to stay unique across
     * instances. Same reasoning as {@code OrderRepository.nextOrderNumber}.
     */
    String nextPoNumber(LocalDate date);

    /** Empty when no such supplier exists. */
    Optional<SupplierStatus> supplierStatus(UUID supplierId);

    /**
     * Candidates for BR-PO-003 ("two POs to the same supplier, same SKU, same delivery date are
     * flagged as possible duplicates") — open orders for this supplier due the same date.
     */
    List<PurchaseOrder> findOpenBySupplierAndExpectedAt(UUID supplierId, LocalDate expectedAt);

    Optional<PurchaseOrder> findByIdForUpdate(PurchaseOrderId id);

    /** A goods receipt of this order is still being counted (DRAFT): the order cannot be cancelled under it. */
    boolean hasReceiptInProgress(PurchaseOrderId id);

    /**
     * The number a new line of this order gets: one past every number it has used, withdrawn lines
     * included ({@code uk_purchase_order_lines_no}).
     */
    int nextLineNo(PurchaseOrderId id);

    /** The next revision number: 0 for an order never submitted. */
    long nextRevisionNo(PurchaseOrderId id);

    /**
     * Freezes the order as it is now: header snapshot and one line revision per line, and points the
     * lines at it. Returns the revision's id.
     */
    UUID recordRevision(PurchaseOrder order, long revisionNo, UUID actorId, String changeSummary);

    /** An approval decision on a revision: APPROVED, or REJECTED with a reason. */
    void recordApproval(UUID revisionId, UUID approverId, String decision, String reason);

    /**
     * An automatic approval (SCRUM-124 rule 1): decision {@code AUTO_APPROVED} at {@code at}, with the limit that
     * allowed it and whose limit it was ({@code limitHolderId}: the submitter, or for a subcontract order the person
     * who split the production order). {@code approverId} is the person the system approved on behalf of.
     */
    void recordAutoApproval(UUID revisionId, UUID approverId, UUID limitHolderId, ApprovalLimit limit, Instant at,
                            String reason);

    /**
     * Whether any revision of the order was ever rejected (SCRUM-124 rule 3): such an order is approved by a person
     * whatever it totals now.
     */
    boolean hasBeenRejected(PurchaseOrderId id);

    /** Who raised the order: the actor of its CREATED event; empty for one with none. */
    Optional<UUID> creatorOf(PurchaseOrderId id);

    /**
     * The anti-splitting sum (SCRUM-124 rule 4): the current totals of the orders to {@code supplierId} in
     * {@code currency}, other than {@code except}, that were approved automatically on {@code limitHolderId}'s limit
     * since {@code since}, cancelled ones left out. Serialised per holder and supplier until the transaction ends, so
     * two submissions racing cannot both be counted as the first.
     */
    Money autoApprovedTotal(UUID limitHolderId, UUID supplierId, Currency currency, Instant since, PurchaseOrderId except);

    /** One line of the order's history; {@code action} is one of {@code ck_purchase_order_events_action}. */
    void recordEvent(PurchaseOrderId id, UUID revisionId, String action, UUID actorId, PurchaseOrderStatus from,
                     PurchaseOrderStatus to, String reason);

    /** As above, with facts for the timeline ({@code payload}): what a draft edit changed. */
    void recordEvent(PurchaseOrderId id, UUID revisionId, String action, UUID actorId, PurchaseOrderStatus from,
                     PurchaseOrderStatus to, String reason, Map<String, String> payload);
}

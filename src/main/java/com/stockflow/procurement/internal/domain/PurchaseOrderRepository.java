package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRepository;
import com.stockflow.common.domain.Money;

import java.math.BigDecimal;
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

    /**
     * A goods receipt of this order is still being counted (DRAFT) or waits for the manager (PENDING_APPROVAL): the
     * order cannot be cancelled under it.
     */
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

    // ------------------------------------------------------------------ amendments (SCRUM-117)

    /**
     * Freezes an amendment as revision {@code revisionNo} (kind AMENDMENT): the header snapshot of the order as
     * {@code proposal} would leave it - the keys of {@link #recordRevision} plus {@code previousTotalAmount} - and one
     * line revision per kept line, with its proposed content and the packing unit it stays in; {@code change_summary}
     * is the reason. The live lines are not touched: until it is approved, an amendment is a request, and what was
     * ordered is still what receiving counts against. Returns the revision's id.
     */
    UUID recordAmendment(PurchaseOrder order, long revisionNo, UUID actorId, PurchaseOrder.ProposedAmendment proposal);

    /**
     * The amendment of an approved order that waits for a decision.
     *
     * @param requestedBy who asked for it: the approver must be someone else (four eyes)
     * @param totalAmount what the order totals once it is approved, after tax: what an approval limit is held against
     */
    record PendingAmendment(UUID revisionId, long revisionNo, UUID requestedBy, Instant requestedAt, String reason,
                            BigDecimal totalAmount) {
    }

    /**
     * The amendment waiting on an approved order; empty when none waits. A resubmission of a rejected draft is a
     * pending AMENDMENT revision too, but of an order still PENDING_APPROVAL, which is not an amendment of an approved
     * order and is left out.
     */
    Optional<PendingAmendment> pendingAmendment(PurchaseOrderId id);

    /**
     * Writes an approved amendment's content into the order, in SQL: each kept line takes its line revision's quantity,
     * unit count, price, tax rate, description and amounts in the same UPDATE that points it at the revision (the only
     * shape {@code guard_po_line_content_change} accepts outside DRAFT); a live line the revision does not hold becomes
     * CANCELLED; the header takes the revision's expected date and note, and totals over the lines still ordered.
     * The order and its lines are refreshed afterwards, so a later JPA read sees what the SQL wrote.
     *
     * <p>The revision pointers, the revision number and the supplier's response are not written here: they are the
     * aggregate's ({@link PurchaseOrder#applyApprovedAmendment}), which the caller applies to the order it reloads
     * after this and saves.</p>
     *
     * @throws IllegalStateException when {@code revisionId} is not the AMENDMENT number {@code revisionNo} waiting on
     *                               this order; nothing is written then, since the transaction rolls back
     */
    void applyAmendment(PurchaseOrderId id, UUID revisionId, long revisionNo);

    /**
     * Clears the pending amendment pointer in SQL, for a writer that holds no aggregate (receiving). The caller has
     * the order locked, found the amendment with {@link #pendingAmendment}, and records the decision itself.
     */
    void dropAmendment(PurchaseOrderId id);

    /** One line of a revision as it froze it; {@code description} is the line's note. */
    record RevisionLine(UUID lineId, int lineNo, String sku, int quantityOrdered, BigDecimal unitPrice,
                        BigDecimal taxRate, String description) {
    }

    /** The lines a revision froze, by line number. */
    List<RevisionLine> revisionLines(UUID revisionId);
}

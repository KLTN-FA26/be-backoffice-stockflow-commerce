package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Aggregate root of the procurement module: one purchase order to one supplier, received into
 * one warehouse</b> ({@code procurement.purchase_orders}).
 *
 * <p>The lifecycle is {@link PurchaseOrderStatus}'s (decision D4). What this class decides: the order
 * of the steps; that whoever approves did not submit (four-eyes, BR-PO-002, stated again by
 * {@code ck_purchase_orders_four_eyes}); that an order leaving DRAFT does so with a revision — the
 * frozen copy of what was approved ({@code ck_purchase_orders_revision}); the delivery-date rules of
 * sending (#36); that the supplier's answer is final once given; and that only an order with nothing
 * received may be cancelled. Receiving is not here: a goods receipt moves CONFIRMED and
 * PARTIALLY_RECEIVED forward through its own port.</p>
 *
 * <p>Supplier, warehouse and number are fixed at creation ({@code tg_purchase_orders_immutable}).
 * {@code totalAmount} is derived from the lines and cannot be set on its own.</p>
 *
 * <p>No Spring, no JPA, no annotations — {@code ArchitectureTest.domainDoesNotDependOnFrameworks}.</p>
 */
public final class PurchaseOrder extends AggregateRoot {

    public enum Type { STANDARD, SUBCONTRACT }

    private final PurchaseOrderId id;
    private final String poNumber;
    private final Type type;
    private final UUID productionOrderId;
    private final UUID supplierId;
    private final UUID warehouseId;
    private PurchaseOrderStatus status;
    private final Currency currency;
    private final List<PoLine> lines;
    private final LocalDate orderDate;
    private LocalDate expectedAt;
    private final String note;
    private final int paymentTermDays;
    private final int leadTimeDays;

    private long revisionNo;
    private UUID activeRevisionId;
    private UUID pendingRevisionId;
    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private UUID confirmedBy;
    private Instant confirmedAt;
    private UUID closedBy;
    private Instant closedAt;
    private CloseKind closeKind;
    private String closeReason;
    private String cancelReason;

    private SupplierConfirmationStatus supplierConfirmationStatus;
    private Instant supplierRespondedAt;
    private String supplierReference;
    private String supplierResponseNote;

    private final long version;
    private final Instant createdAt;
    private final String createdBy;
    private final Instant lastModifiedAt;
    private final String lastModifiedBy;

    /** Rehydration: every field as stored. */
    public record State(PurchaseOrderId id, String poNumber, Type type, UUID productionOrderId, UUID supplierId,
                        UUID warehouseId, PurchaseOrderStatus status, Currency currency, List<PoLine> lines,
                        LocalDate orderDate, LocalDate expectedAt, String note, int paymentTermDays,
                        int leadTimeDays, long revisionNo, UUID activeRevisionId, UUID pendingRevisionId,
                        UUID submittedBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
                        UUID confirmedBy, Instant confirmedAt, UUID closedBy, Instant closedAt,
                        CloseKind closeKind, String closeReason, String cancelReason,
                        SupplierConfirmationStatus supplierConfirmationStatus, Instant supplierRespondedAt,
                        String supplierReference, String supplierResponseNote, long version, Instant createdAt,
                        String createdBy, Instant lastModifiedAt, String lastModifiedBy) {
    }

    public PurchaseOrder(State s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.poNumber = requireNonBlank(s.poNumber(), "poNumber");
        this.type = Objects.requireNonNull(s.type(), "type");
        this.productionOrderId = s.productionOrderId();
        this.supplierId = Objects.requireNonNull(s.supplierId(), "supplierId");
        this.warehouseId = Objects.requireNonNull(s.warehouseId(), "warehouseId");
        this.status = Objects.requireNonNull(s.status(), "status");
        this.currency = Objects.requireNonNull(s.currency(), "currency");
        this.lines = new ArrayList<>(s.lines() == null ? List.of() : s.lines());
        if (this.lines.isEmpty()) {
            throw new IllegalArgumentException("A purchase order must have at least one line");
        }
        for (PoLine line : this.lines) {
            if (!line.unitPrice().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "Line currency " + line.unitPrice().currency() + " does not match order currency " + currency);
            }
        }
        this.orderDate = Objects.requireNonNull(s.orderDate(), "orderDate");
        this.expectedAt = s.expectedAt();
        this.note = s.note();
        this.paymentTermDays = s.paymentTermDays();
        this.leadTimeDays = s.leadTimeDays();
        this.revisionNo = s.revisionNo();
        this.activeRevisionId = s.activeRevisionId();
        this.pendingRevisionId = s.pendingRevisionId();
        this.submittedBy = s.submittedBy();
        this.submittedAt = s.submittedAt();
        this.approvedBy = s.approvedBy();
        this.approvedAt = s.approvedAt();
        this.confirmedBy = s.confirmedBy();
        this.confirmedAt = s.confirmedAt();
        this.closedBy = s.closedBy();
        this.closedAt = s.closedAt();
        this.closeKind = s.closeKind();
        this.closeReason = s.closeReason();
        this.cancelReason = s.cancelReason();
        this.supplierConfirmationStatus = Objects.requireNonNull(s.supplierConfirmationStatus(),
                "supplierConfirmationStatus");
        this.supplierRespondedAt = s.supplierRespondedAt();
        this.supplierReference = s.supplierReference();
        this.supplierResponseNote = s.supplierResponseNote();
        this.version = s.version();
        this.createdAt = s.createdAt();
        this.createdBy = s.createdBy();
        this.lastModifiedAt = s.lastModifiedAt();
        this.lastModifiedBy = s.lastModifiedBy();
    }

    /** A new standard purchase order, always born {@link PurchaseOrderStatus#DRAFT}. */
    public static PurchaseOrder draft(String poNumber, UUID supplierId, UUID warehouseId, Currency currency,
                                      List<PoLine> lines, LocalDate orderDate, LocalDate expectedAt, String note,
                                      int paymentTermDays, int leadTimeDays) {
        if (expectedAt != null && expectedAt.isBefore(orderDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The expected delivery date cannot be before the order date");
        }
        return new PurchaseOrder(new State(PurchaseOrderId.newId(), poNumber, Type.STANDARD, null, supplierId,
                warehouseId, PurchaseOrderStatus.DRAFT, currency, lines, orderDate, expectedAt, note, paymentTermDays,
                leadTimeDays, 0, null, null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierConfirmationStatus.NOT_SENT, null, null, null, 0L, null, null, null, null));
    }

    /** Line numbers of a new order: 1, 2, 3… in the order given. */
    public static PoLine line(int lineNo, UUID inventoryItemId, com.stockflow.common.domain.Sku sku, String uom,
                              String description, int quantity, Money unitPrice, BigDecimal taxRate) {
        return PoLine.draft(com.stockflow.common.id.Identifiers.newId(), lineNo, inventoryItemId, sku, uom,
                description, quantity, unitPrice, taxRate);
    }

    // ------------------------------------------------------------------ approval

    /**
     * DRAFT → PENDING_APPROVAL, with the revision that freezes what is being approved. A first
     * submission is revision 0 (INITIAL); a resubmission after a rejection is the next number.
     */
    public void submit(UUID userId, UUID revisionId, long revisionNo, Instant now) {
        requireCanTransitionTo(PurchaseOrderStatus.PENDING_APPROVAL);
        this.status = PurchaseOrderStatus.PENDING_APPROVAL;
        this.submittedBy = Objects.requireNonNull(userId, "userId");
        this.submittedAt = Objects.requireNonNull(now, "now");
        this.pendingRevisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.revisionNo = revisionNo;
    }

    /** PENDING_APPROVAL → APPROVED. BR-PO-002 four-eyes: the approver did not submit it. */
    public void approve(UUID approverId, Instant now) {
        requireCanTransitionTo(PurchaseOrderStatus.APPROVED);
        Objects.requireNonNull(approverId, "approverId");
        if (approverId.equals(submittedBy)) {
            throw new BusinessException(ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                    "A purchase order is approved by someone other than who submitted it");
        }
        this.status = PurchaseOrderStatus.APPROVED;
        this.approvedBy = approverId;
        this.approvedAt = Objects.requireNonNull(now, "now");
        this.activeRevisionId = pendingRevisionId;
        this.pendingRevisionId = null;
    }

    /** PENDING_APPROVAL → DRAFT with a reason; the rejected revision stays as history. */
    public void reject(String reason) {
        requireCanTransitionTo(PurchaseOrderStatus.DRAFT);
        requireReason(reason, 500);
        this.status = PurchaseOrderStatus.DRAFT;
        this.submittedBy = null;
        this.submittedAt = null;
        this.pendingRevisionId = null;
    }

    // ------------------------------------------------------------------ sending (#36)

    /**
     * APPROVED → CONFIRMED: the content is locked and the order goes to the supplier (plan Q1). A
     * delivery date is required; replacing a date already agreed needs a reason.
     */
    public void confirm(UUID userId, LocalDate replacement, String reason, Instant now) {
        requireCanTransitionTo(PurchaseOrderStatus.CONFIRMED);
        LocalDate candidate = replacement == null ? expectedAt : replacement;
        if (candidate == null) {
            throw new BusinessException(ErrorCode.PO_DELIVERY_DATE_REQUIRED);
        }
        if (candidate.isBefore(orderDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The expected delivery date cannot be before the order date");
        }
        if (expectedAt != null && replacement != null && !replacement.equals(expectedAt)) {
            requireReason(reason, 1000);
        }
        this.expectedAt = candidate;
        this.status = PurchaseOrderStatus.CONFIRMED;
        this.confirmedBy = Objects.requireNonNull(userId, "userId");
        this.confirmedAt = Objects.requireNonNull(now, "now");
        this.supplierConfirmationStatus = SupplierConfirmationStatus.PENDING;
    }

    public void requireDeliveryRecovery(LocalDate today, String reason, boolean reconciled, boolean acknowledgePastDue) {
        if (status != PurchaseOrderStatus.CONFIRMED || supplierConfirmationStatus != SupplierConfirmationStatus.PENDING)
            throw new InvalidPurchaseOrderTransitionException(
                    id, "Delivery recovery requires CONFIRMED with a pending supplier response");
        requireReason(reason, 1000);
        if (!reconciled)
            throw new BusinessException(ErrorCode.CONFLICT, "Reconcile the previous delivery outcome before retrying");
        if ((expectedAt == null || expectedAt.isBefore(today)) && !acknowledgePastDue)
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Explicitly acknowledge the original overdue or unknown delivery date; recovery cannot amend a"
                            + " confirmed PO");
    }

    /** The supplier's answer. Final once given; a refusal says why and comes before any receipt. */
    public void recordSupplierConfirmation(SupplierConfirmationStatus response, String reference, String note,
                                           Instant now) {
        if (status != PurchaseOrderStatus.CONFIRMED && status != PurchaseOrderStatus.PARTIALLY_RECEIVED
                && status != PurchaseOrderStatus.RECEIVED && status != PurchaseOrderStatus.CLOSED) {
            throw new InvalidPurchaseOrderTransitionException(id, "supplier response requires a confirmed purchase order");
        }
        if (status == PurchaseOrderStatus.CLOSED && supplierConfirmationStatus == SupplierConfirmationStatus.NOT_SENT) {
            throw new InvalidPurchaseOrderTransitionException(id, "supplier response requires a sent purchase order");
        }
        if (response != SupplierConfirmationStatus.CONFIRMED && response != SupplierConfirmationStatus.REJECTED) {
            throw new BusinessException(ErrorCode.PO_SUPPLIER_RESPONSE_INVALID);
        }
        reference = reference == null || reference.isBlank() ? null : reference.trim();
        note = note == null || note.isBlank() ? null : note.trim();
        if (response == SupplierConfirmationStatus.REJECTED && note == null) {
            throw new BusinessException(ErrorCode.PO_REASON_REQUIRED);
        }
        if (response == SupplierConfirmationStatus.REJECTED && status != PurchaseOrderStatus.CONFIRMED) {
            throw new InvalidPurchaseOrderTransitionException(id, "Cannot reject a purchase order after receipt");
        }
        if (supplierConfirmationStatus == response) {
            if (Objects.equals(supplierReference, reference) && Objects.equals(supplierResponseNote, note)) return;
            throw new InvalidPurchaseOrderTransitionException(id, "A recorded supplier response cannot be overwritten");
        }
        if (supplierConfirmationStatus != SupplierConfirmationStatus.PENDING) {
            throw new InvalidPurchaseOrderTransitionException(id, "supplier response is already final");
        }
        this.supplierConfirmationStatus = response;
        this.supplierRespondedAt = now;
        this.supplierReference = reference;
        this.supplierResponseNote = note;
    }

    // ------------------------------------------------------------------ ending

    /**
     * → CANCELLED, with a reason. Only while nothing has been received: receiving moves the order to
     * PARTIALLY_RECEIVED or RECEIVED, from which the table offers no cancel; a receipt still being
     * counted is the service's check.
     */
    public void cancel(String reason) {
        requireCancellable(reason);
        if (activeRevisionId == null && pendingRevisionId == null) {
            throw new IllegalStateException("A cancelled order keeps the revision it was cancelled at; freeze it first");
        }
        this.status = PurchaseOrderStatus.CANCELLED;
        this.cancelReason = reason.trim();
        lines.forEach(line -> line.settle(PoLineStatus.CANCELLED));
    }

    /** The checks of {@link #cancel}, for the caller to run before it writes anything append-only. */
    public void requireCancellable(String reason) {
        requireCanTransitionTo(PurchaseOrderStatus.CANCELLED);
        requireReason(reason, 255);
    }

    /**
     * A draft that never went for approval has no revision, and only a draft may lack one
     * ({@code ck_purchase_orders_revision}): cancelling it first freezes what is being cancelled.
     */
    public void freeze(UUID revisionId, long revisionNo) {
        if (status != PurchaseOrderStatus.DRAFT || activeRevisionId != null || pendingRevisionId != null) {
            throw new InvalidPurchaseOrderTransitionException(id, "only a draft without a revision is frozen this way");
        }
        this.activeRevisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.revisionNo = revisionNo;
    }

    /** PARTIALLY_RECEIVED → CLOSED (SHORT_CLOSE): the open remainder is written off with a reason. */
    public void closeShort(String reason, UUID userId, Instant now) {
        if (status != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new InvalidPurchaseOrderTransitionException(id, status, PurchaseOrderStatus.CLOSED);
        }
        requireReason(reason, 255);
        close(CloseKind.SHORT_CLOSE, reason.trim(), userId, now);
    }

    /** RECEIVED → CLOSED (NORMAL): everything arrived; the order is done. */
    public void close(UUID userId, Instant now) {
        if (status != PurchaseOrderStatus.RECEIVED) {
            throw new InvalidPurchaseOrderTransitionException(id, status, PurchaseOrderStatus.CLOSED);
        }
        close(CloseKind.NORMAL, null, userId, now);
    }

    private void close(CloseKind kind, String reason, UUID userId, Instant now) {
        this.status = PurchaseOrderStatus.CLOSED;
        this.closeKind = kind;
        this.closeReason = reason;
        this.closedBy = Objects.requireNonNull(userId, "userId");
        this.closedAt = Objects.requireNonNull(now, "now");
        lines.forEach(line -> line.settle(PoLineStatus.CLOSED));
    }

    // ------------------------------------------------------------------ amounts

    public Money subtotal() {
        return new Money(lines.stream().map(PoLine::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add), currency);
    }

    public Money taxTotal() {
        return new Money(lines.stream().map(PoLine::tax).reduce(BigDecimal.ZERO, BigDecimal::add), currency);
    }

    public Money totalAmount() {
        return subtotal().plus(taxTotal());
    }

    private void requireCanTransitionTo(PurchaseOrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidPurchaseOrderTransitionException(id, status, target);
        }
    }

    private static void requireReason(String reason, int max) {
        if (reason == null || reason.isBlank() || reason.trim().length() > max)
            throw new BusinessException(ErrorCode.PO_REASON_REQUIRED,
                    "A reason of 1..%d characters is required".formatted(max));
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public PurchaseOrderId id() { return id; }
    public String poNumber() { return poNumber; }
    public Type type() { return type; }
    public UUID productionOrderId() { return productionOrderId; }
    public UUID supplierId() { return supplierId; }
    public UUID warehouseId() { return warehouseId; }
    public PurchaseOrderStatus status() { return status; }
    public Currency currency() { return currency; }
    public List<PoLine> lines() { return List.copyOf(lines); }
    public LocalDate orderDate() { return orderDate; }
    public LocalDate expectedAt() { return expectedAt; }
    public String note() { return note; }
    public int paymentTermDays() { return paymentTermDays; }
    public int leadTimeDays() { return leadTimeDays; }
    public long revisionNo() { return revisionNo; }
    public UUID activeRevisionId() { return activeRevisionId; }
    public UUID pendingRevisionId() { return pendingRevisionId; }
    public UUID submittedBy() { return submittedBy; }
    public Instant submittedAt() { return submittedAt; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public UUID confirmedBy() { return confirmedBy; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID closedBy() { return closedBy; }
    public Instant closedAt() { return closedAt; }
    public CloseKind closeKind() { return closeKind; }
    public String closeReason() { return closeReason; }
    public String cancelReason() { return cancelReason; }
    public SupplierConfirmationStatus supplierConfirmationStatus() { return supplierConfirmationStatus; }
    public Instant supplierRespondedAt() { return supplierRespondedAt; }
    public String supplierReference() { return supplierReference; }
    public String supplierResponseNote() { return supplierResponseNote; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }
    public Instant lastModifiedAt() { return lastModifiedAt; }
    public String lastModifiedBy() { return lastModifiedBy; }
}

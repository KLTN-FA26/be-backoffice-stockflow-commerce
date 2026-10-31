package com.stockflow.procurement.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Aggregate root of the procurement module: one purchase order to one supplier, received into
 * one warehouse</b> ({@code procurement.purchase_orders}).
 *
 * <p>The lifecycle is {@link PurchaseOrderStatus}'s (decision D4). What this class decides: the order
 * of the steps; that whoever approves did not submit (four-eyes, BR-PO-002, stated again by
 * {@code ck_purchase_orders_four_eyes}) - except an order approved at submission within the submitter's own
 * approval limit, which nobody else decides (kltn-docs 02 §4 step 7, {@link #submitWithinLimit}); that an
 * approver's own limit covers the total ({@link #approve}); that an order leaving DRAFT does so with a revision — the
 * frozen copy of what was approved ({@code ck_purchase_orders_revision}); the delivery-date rules of
 * sending (#36); that the supplier's answer is final once given; and that only an order with nothing
 * received may be cancelled. Receiving is not here: a goods receipt moves CONFIRMED and
 * PARTIALLY_RECEIVED forward through its own port.</p>
 *
 * <p>Supplier and number are fixed at creation ({@code tg_purchase_orders_immutable}); the warehouse,
 * date, note and lines change only while a standard order is DRAFT ({@link #reviseDraft}, SCRUM-462,
 * stated again by {@code tg_purchase_orders_warehouse_draft_only} and
 * {@code tg_purchase_order_lines_draft_content}). {@code totalAmount} is derived from the lines and
 * cannot be set on its own.</p>
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
    private UUID warehouseId;
    private PurchaseOrderStatus status;
    private final Currency currency;
    private final List<PoLine> lines;
    private final LocalDate orderDate;
    private LocalDate expectedAt;
    private String note;
    private final int paymentTermDays;
    /** The slowest line's lead time; a draft edit recomputes it from the lines as edited (SCRUM-78). */
    private int leadTimeDays;

    private long revisionNo;
    private UUID activeRevisionId;
    private UUID pendingRevisionId;
    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    /** Approved at submission, within the submitter's own limit: nobody else decided it (docs 02 §4 step 7). */
    private boolean autoApproved;
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
                        boolean autoApproved, UUID confirmedBy, Instant confirmedAt, UUID closedBy, Instant closedAt,
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
        this.autoApproved = s.autoApproved();
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
                leadTimeDays, 0, null, null, null, null, null, null, false, null, null, null, null, null, null, null,
                SupplierConfirmationStatus.NOT_SENT, null, null, null, 0L, null, null, null, null));
    }

    /**
     * A new SUBCONTRACT purchase order (SCRUM-434): the print work for one SUBCONTRACTED production
     * order, one line, born {@link PurchaseOrderStatus#DRAFT}. From there it is submitted, approved and
     * received like any other order. The database states the rest again ({@code check_subcontract_po}:
     * print subcontractor, production order of the same warehouse, one line, one live order per
     * production order).
     */
    public static PurchaseOrder subcontract(String poNumber, UUID productionOrderId, UUID supplierId, UUID warehouseId,
                                            Currency currency, PoLine line, LocalDate orderDate, LocalDate expectedAt,
                                            int paymentTermDays, int leadTimeDays) {
        Objects.requireNonNull(productionOrderId, "productionOrderId");
        if (expectedAt != null && expectedAt.isBefore(orderDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The expected delivery date cannot be before the order date");
        }
        return new PurchaseOrder(new State(PurchaseOrderId.newId(), poNumber, Type.SUBCONTRACT, productionOrderId,
                supplierId, warehouseId, PurchaseOrderStatus.DRAFT, currency, List.of(line), orderDate, expectedAt,
                null, paymentTermDays, leadTimeDays, 0, null, null, null, null, null, null, false, null, null, null,
                null, null, null, null, SupplierConfirmationStatus.NOT_SENT, null, null, null, 0L, null, null, null, null));
    }

    /** Line numbers of a new order: 1, 2, 3… in the order given. */
    public static PoLine line(int lineNo, UUID inventoryItemId, com.stockflow.common.domain.Sku sku, String uom,
                              String description, int quantity, Money unitPrice, BigDecimal taxRate) {
        return line(lineNo, inventoryItemId, sku, uom, description, quantity, unitPrice, taxRate, null);
    }

    /** A new line in {@code packingUnit} (SCRUM-473), null for base units. */
    public static PoLine line(int lineNo, UUID inventoryItemId, com.stockflow.common.domain.Sku sku, String uom,
                              String description, int quantity, Money unitPrice, BigDecimal taxRate,
                              PackingUnit packingUnit) {
        return PoLine.draft(com.stockflow.common.id.Identifiers.newId(), lineNo, inventoryItemId, sku, uom,
                description, quantity, unitPrice, taxRate, packingUnit);
    }

    // ------------------------------------------------------------------ editing a draft (SCRUM-462)

    /**
     * One line of a draft as the buyer now wants it. {@code lineId} names a line the draft already
     * has, which keeps its number and its item; null is a new line.
     */
    public record DraftLine(UUID lineId, UUID inventoryItemId, com.stockflow.common.domain.Sku sku, String uom,
                            String description, int quantity, Money unitPrice, BigDecimal taxRate, PackingUnit unit,
                            boolean unitNamed) {

        /** Naming no unit: a new line is in base units, an existing one keeps its own. */
        public DraftLine(UUID lineId, UUID inventoryItemId, com.stockflow.common.domain.Sku sku, String uom,
                         String description, int quantity, Money unitPrice, BigDecimal taxRate) {
            this(lineId, inventoryItemId, sku, uom, description, quantity, unitPrice, taxRate, null, false);
        }
    }

    /**
     * kltn-docs 02 §5: a DRAFT is changed freely — warehouse, delivery date, note and lines — and
     * only a DRAFT; from submission on, what was ordered changes through a revision (BR-02). A
     * SUBCONTRACT order is not edited here: its line follows the production split (BR-10).
     *
     * <p>A line the draft had and {@code lines} does not name leaves the order; the repository keeps
     * its row as WITHDRAWN when a revision already froze it. New lines are numbered from
     * {@code firstNewLineNo}, past every number the order has ever used, so a number is never
     * reused.</p>
     *
     * <p>{@code leadTimeDays} is the slowest line's as the catalog has it now, which the service works out:
     * the lines change, so the lead time they need does too.</p>
     */
    public void reviseDraft(UUID warehouseId, LocalDate expectedAt, String note, List<DraftLine> lines,
                            int firstNewLineNo, int leadTimeDays) {
        if (status != PurchaseOrderStatus.DRAFT || type != Type.STANDARD) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_EDITABLE,
                    "Purchase order %s is %s %s".formatted(poNumber, status, type));
        }
        Objects.requireNonNull(warehouseId, "warehouseId");
        if (expectedAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The expected delivery date is required");
        }
        if (expectedAt.isBefore(orderDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The expected delivery date cannot be before the order date");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A purchase order must have at least one line");
        }
        Map<UUID, PoLine> current = new HashMap<>();
        this.lines.forEach(line -> current.put(line.id(), line));
        Set<UUID> kept = new HashSet<>();
        Set<UUID> items = new HashSet<>();
        int nextLineNo = Math.max(firstNewLineNo,
                this.lines.stream().mapToInt(PoLine::lineNo).max().orElse(0) + 1);
        List<PoLine> revised = new ArrayList<>();
        for (DraftLine wanted : lines) {
            if (!wanted.unitPrice().currency().equals(currency)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Every line is priced in the order's currency " + currency.getCurrencyCode());
            }
            if (!items.add(wanted.inventoryItemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "SKU " + wanted.sku().code() + " appears twice; put the whole quantity on one line");
            }
            if (wanted.lineId() == null) {
                revised.add(PoLine.draft(com.stockflow.common.id.Identifiers.newId(), nextLineNo++,
                        wanted.inventoryItemId(), wanted.sku(), wanted.uom(), wanted.description(), wanted.quantity(),
                        wanted.unitPrice(), wanted.taxRate(), wanted.unit()));
                continue;
            }
            PoLine existing = current.get(wanted.lineId());
            if (existing == null || !kept.add(wanted.lineId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Line " + wanted.lineId() + " is not a line of " + poNumber + ", or is named twice");
            }
            if (!existing.inventoryItemId().equals(wanted.inventoryItemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Line %d orders %s; to order another SKU, remove the line and add a new one"
                                .formatted(existing.lineNo(), existing.sku().code()));
            }
            // SCRUM-473: a client that names no unit keeps the line's, so one that knows nothing of units cannot drop
            // it; the quantity must then be a whole number of it. Naming the base unit is how a line leaves one.
            PackingUnit unit = wanted.unitNamed() ? wanted.unit()
                    : existing.packingUnit() == null ? null
                    : existing.packingUnit().withQuantity(poNumber, wanted.quantity());
            revised.add(PoLine.draft(existing.id(), existing.lineNo(), existing.inventoryItemId(), existing.sku(),
                    existing.uom(), wanted.description(), wanted.quantity(), wanted.unitPrice(), wanted.taxRate(),
                    unit));
        }
        this.warehouseId = warehouseId;
        this.expectedAt = expectedAt;
        this.note = note;
        this.leadTimeDays = leadTimeDays;
        this.lines.clear();
        this.lines.addAll(revised);
    }

    // ------------------------------------------------------------------ amending an approved order (SCRUM-117)

    /**
     * One line of an amendment as the buyer now wants it, keyed by the line it changes. Price, tax and description
     * left null keep the line's own: the price was agreed when the order was approved, so a missing one must never
     * fall back to the catalog's of today. The quantity is in base units; a line in a packing unit stays in it.
     */
    public record AmendmentLine(UUID lineId, Integer quantityOrdered, BigDecimal unitPrice, BigDecimal taxRate,
                                String description) {
    }

    /**
     * An amendment as requested: the header as it should read afterwards, and <b>every line kept</b> - a live line
     * the request leaves out is removed, as in {@link #reviseDraft}.
     */
    public record AmendmentRequest(LocalDate expectedAt, String note, String reason, List<AmendmentLine> lines) {
    }

    /**
     * A kept line before and after. {@code after} is a real {@link PoLine} - same id, number, item, SKU and unit,
     * only the count of the unit follows the quantity - so its amounts come from the one place that computes them.
     */
    public record ProposedLine(PoLine before, PoLine after) {

        public boolean quantityChanged() {
            return before.quantityOrdered() != after.quantityOrdered();
        }

        /** Only an increase asks the catalog and the SKU's status again: goods already ordered are still received. */
        public boolean quantityIncreased() {
            return after.quantityOrdered() > before.quantityOrdered();
        }

        public boolean changed() {
            return quantityChanged()
                    || before.unitPrice().amount().compareTo(after.unitPrice().amount()) != 0
                    || before.taxRate().compareTo(after.taxRate()) != 0
                    || !Objects.equals(before.description(), after.description());
        }
    }

    /**
     * What the order would be once the amendment is approved: the content of the revision to record, and the facts
     * the timeline shows. {@code kept} follows the request's order, so the index of a kept line is the index of the
     * request line a later check reports a fault on.
     *
     * @param changes the facts that differ, before and after, keyed like a draft edit's timeline
     *                ({@code line3.quantityBefore}, {@code line5.removed}, {@code expectedAtBefore}, ...)
     */
    public record ProposedAmendment(LocalDate expectedAt, String note, String reason, List<ProposedLine> kept,
                                    List<PoLine> removed, Money subtotal, Money taxTotal, Money totalAmount,
                                    Map<String, String> changes) {
    }

    /**
     * Works out what {@code request} would make of this order, changing nothing: nothing is persisted until the
     * amendment is approved, and a request that fails here must leave no trace. Refused with
     * {@code PO_AMENDMENT_NOT_ALLOWED} unless the order is a standard one, APPROVED or CONFIRMED, not refused by its
     * supplier and with nothing received; with {@code PO_AMENDMENT_ALREADY_PENDING} while another amendment waits;
     * with one {@code VALIDATION_FAILED} naming every field at fault; and with {@code PO_AMENDMENT_NO_CHANGE} when
     * the result would read exactly as the order does now. The supplier's catalog terms and the SKU's status are the
     * service's to check, on the lines this returns.
     */
    public ProposedAmendment proposeAmendment(AmendmentRequest request) {
        requireAmendable();
        if (pendingRevisionId != null) {
            throw alreadyPending();
        }
        List<FieldError> errors = new ArrayList<>();
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            errors.add(new FieldError("reason", "A reason of 1..500 characters is required", "Size"));
        }
        String newNote = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (newNote != null && newNote.length() > 2000) {
            errors.add(new FieldError("note", "The note must be at most 2000 characters", "Size"));
        }
        if (request.expectedAt() == null) {
            errors.add(new FieldError("expectedDate", "The expected delivery date is required", "NotNull"));
        } else if (request.expectedAt().isBefore(orderDate)) {
            errors.add(new FieldError("expectedDate", "The expected delivery date cannot be before the order date",
                    "DateBeforeOrderDate"));
        }
        List<AmendmentLine> wanted = request.lines() == null ? List.of() : request.lines();
        if (wanted.isEmpty()) {
            errors.add(new FieldError("lines", "An amended purchase order keeps at least one line", "Size"));
        }

        Map<UUID, PoLine> current = new HashMap<>();
        this.lines.stream().filter(this::stillOrdered).forEach(line -> current.put(line.id(), line));
        Set<UUID> named = new HashSet<>();
        for (int i = 0; i < wanted.size(); i++) {
            AmendmentLine line = wanted.get(i);
            String at = "lines[" + i + "].";
            if (line == null) {
                errors.add(new FieldError("lines[" + i + "]", "The line is empty", "NotNull"));
                continue;
            }
            // A line already CANCELLED or WITHDRAWN is not a line of the order any more, so it is as unknown as a
            // line of another order: a WITHDRAWN one is never loaded, and a CANCELLED one (an earlier amendment
            // removed it) is left out of `current`.
            if (line.lineId() == null || !current.containsKey(line.lineId()) || !named.add(line.lineId())) {
                errors.add(new FieldError(at + "lineId", "Line " + line.lineId() + " is not a live line of "
                        + poNumber + ", or is named twice", "UnknownLine"));
            }
            if (line.quantityOrdered() == null || line.quantityOrdered() < 1 || line.quantityOrdered() > 1_000_000) {
                errors.add(new FieldError(at + "quantityOrdered", "The quantity must be between 1 and 1,000,000",
                        "Range"));
            }
            if (line.unitPrice() != null && !PurchasePrices.acceptable(line.unitPrice(), currency)) {
                errors.add(new FieldError(at + "unitPrice", "The unit price must be positive and within what "
                        + currency.getCurrencyCode() + " can hold", "Range"));
            }
            if (line.taxRate() != null && (line.taxRate().signum() < 0
                    || line.taxRate().compareTo(BigDecimal.valueOf(100)) > 0)) {
                errors.add(new FieldError(at + "taxRate", "The tax rate must be between 0 and 100", "Range"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    errors.size() + " field(s) of the amendment are invalid", errors);
        }

        List<ProposedLine> kept = new ArrayList<>();
        for (AmendmentLine line : wanted) {
            PoLine before = current.get(line.lineId());
            int quantity = line.quantityOrdered();
            // The count of the unit follows the quantity; one that is not a whole number of it is refused here.
            PackingUnit unit = before.packingUnit() == null ? null
                    : before.packingUnit().withQuantity(poNumber, quantity);
            Money price = line.unitPrice() == null ? before.unitPrice() : new Money(line.unitPrice(), currency);
            String description = line.description() == null || line.description().isBlank() ? before.description()
                    : line.description().trim();
            kept.add(new ProposedLine(before, new PoLine(before.id(), before.lineNo(), before.inventoryItemId(),
                    before.sku(), before.uom(), description, quantity, 0, price,
                    line.taxRate() == null ? before.taxRate() : line.taxRate(), before.status(), unit)));
        }
        List<PoLine> removed = this.lines.stream().filter(line -> current.containsKey(line.id())
                && !named.contains(line.id())).toList();

        Money newSubtotal = new Money(kept.stream().map(l -> l.after().subtotal()).reduce(BigDecimal.ZERO,
                BigDecimal::add), currency);
        Money newTax = new Money(kept.stream().map(l -> l.after().tax()).reduce(BigDecimal.ZERO, BigDecimal::add),
                currency);
        Money newTotal = newSubtotal.plus(newTax);
        if (newTotal.amount().compareTo(PurchasePrices.MAX) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Purchase order total exceeds the supported limit");
        }

        Map<String, String> changes = new LinkedHashMap<>();
        for (ProposedLine line : kept) {
            String key = "line" + line.before().lineNo() + ".";
            if (line.quantityChanged()) {
                putChange(changes, key + "quantity", line.before().quantityOrdered(), line.after().quantityOrdered());
            }
            if (line.before().unitPrice().amount().compareTo(line.after().unitPrice().amount()) != 0) {
                putChange(changes, key + "unitPrice", line.before().unitPrice().amount().toPlainString(),
                        line.after().unitPrice().amount().toPlainString());
            }
            if (line.before().taxRate().compareTo(line.after().taxRate()) != 0) {
                putChange(changes, key + "taxRate", line.before().taxRate().toPlainString(),
                        line.after().taxRate().toPlainString());
            }
            if (!Objects.equals(line.before().description(), line.after().description())) {
                putChange(changes, key + "description", line.before().description(), line.after().description());
            }
        }
        removed.forEach(line -> changes.put("line" + line.lineNo() + ".removed", "true"));
        if (!request.expectedAt().equals(expectedAt)) {
            putChange(changes, "expectedAt", expectedAt, request.expectedAt());
        }
        if (!Objects.equals(note, newNote)) {
            putChange(changes, "note", note, newNote);
        }
        if (newTotal.compareTo(totalAmount()) != 0) {
            putChange(changes, "totalAmount", totalAmount().amount().toPlainString(),
                    newTotal.amount().toPlainString());
        }
        if (changes.isEmpty()) {
            throw new BusinessException(ErrorCode.PO_AMENDMENT_NO_CHANGE,
                    "The amendment of %s changes nothing".formatted(poNumber));
        }
        return new ProposedAmendment(request.expectedAt(), newNote, reason, List.copyOf(kept), removed, newSubtotal,
                newTax, newTotal, changes);
    }

    private static void putChange(Map<String, String> changes, String key, Object before, Object after) {
        changes.put(key + "Before", truncated(before));
        changes.put(key + "After", truncated(after));
    }

    private static String truncated(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 1000 ? text : text.substring(0, 1000) + "…";
    }

    /**
     * The amendment awaits its decision: only the pointer moves. The order's content, status and supplier response
     * stay as they are until it is approved, so a rejected or withdrawn amendment leaves nothing to undo.
     */
    public void requestAmendment(UUID revisionId) {
        Objects.requireNonNull(revisionId, "revisionId");
        requireAmendable();
        if (pendingRevisionId != null) {
            throw alreadyPending();
        }
        this.pendingRevisionId = revisionId;
    }

    /**
     * What <em>approving</em> needs to hold under the order's lock: an amendment waits, and the order is still
     * amendable - nothing was received since it was requested (the first receipt withdraws it, but an approval that
     * raced it must still be refused rather than rewrite lines goods were received against).
     * Rejecting and withdrawing need only {@link #requirePendingAmendment}: they change no line, and must stay
     * possible when the supplier has refused the order meanwhile, or the pointer would be stranded.
     */
    public void requireAmendmentDecidable() {
        requirePendingAmendment();
        requireAmendable();
    }

    /** An amendment waits for a decision; all rejecting or withdrawing it needs. */
    public void requirePendingAmendment() {
        if (pendingRevisionId == null) {
            throw new BusinessException(ErrorCode.PO_AMENDMENT_NOT_PENDING,
                    "Purchase order %s has no amendment waiting for a decision".formatted(poNumber));
        }
    }

    /**
     * The amendment is approved: it becomes the active revision. The content is not applied here - the adapter
     * writes the lines and the header in SQL and the service reloads the order - so this moves the pointers and
     * settles what the supplier was told. A confirmed order the supplier had answered goes back to waiting for an
     * answer, because what they agreed to is no longer what is ordered (the earlier answer is kept in the event
     * payload); an order not yet sent has no answer to reset.
     */
    public void applyApprovedAmendment(UUID revisionId, long revisionNo) {
        Objects.requireNonNull(revisionId, "revisionId");
        if (!revisionId.equals(pendingRevisionId)) {
            throw new BusinessException(ErrorCode.PO_AMENDMENT_NOT_PENDING,
                    "Revision %s is not the amendment of %s that waits for a decision".formatted(revisionId, poNumber));
        }
        this.activeRevisionId = revisionId;
        this.pendingRevisionId = null;
        this.revisionNo = revisionNo;
        if (status == PurchaseOrderStatus.CONFIRMED && (supplierConfirmationStatus == SupplierConfirmationStatus.PENDING
                || supplierConfirmationStatus == SupplierConfirmationStatus.CONFIRMED)) {
            this.supplierConfirmationStatus = SupplierConfirmationStatus.PENDING;
            this.supplierRespondedAt = null;
            this.supplierReference = null;
            this.supplierResponseNote = null;
        }
    }

    /** The amendment is rejected or withdrawn: the order keeps its active revision, and only the pointer clears. */
    public void dropAmendment() {
        requirePendingAmendment();
        this.pendingRevisionId = null;
    }

    /**
     * The conditions an order has to meet to be amended or to have an amendment decided (design D1/D6): a standard
     * order that is APPROVED or CONFIRMED, whose supplier has not refused it, with nothing received. Once goods
     * arrive, what was ordered is what receiving counted against, and it changes only by closing the order short.
     */
    private void requireAmendable() {
        if (type != Type.STANDARD
                || status != PurchaseOrderStatus.APPROVED && status != PurchaseOrderStatus.CONFIRMED
                || supplierConfirmationStatus == SupplierConfirmationStatus.REJECTED
                || lines.stream().anyMatch(line -> line.quantityReceived() > 0)) {
            throw new BusinessException(ErrorCode.PO_AMENDMENT_NOT_ALLOWED,
                    "Purchase order %s (%s, %s, supplier %s) cannot be amended".formatted(poNumber, type, status,
                            supplierConfirmationStatus));
        }
    }

    private BusinessException alreadyPending() {
        return new BusinessException(ErrorCode.PO_AMENDMENT_ALREADY_PENDING,
                "Purchase order %s already has an amendment waiting for a decision".formatted(poNumber));
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

    /**
     * Whether {@code limit} (a configured approval limit, in this order's currency) covers the order's total, after
     * tax: what is paid to the supplier. A limit of zero or less covers nothing. Having no limit at all is not a limit
     * of zero and never reaches here (owner decision PO-NOLIMIT): the caller asks only when one is configured.
     */
    public boolean coveredBy(Money limit) {
        return limit.amount().signum() > 0 && totalAmount().compareTo(limit) <= 0;
    }

    /**
     * DRAFT → APPROVED at submission, the one way a draft reaches APPROVED (docs 02 §4 step 7 and §5: "Submit (dưới hạn
     * mức)"; owner decision POLIMIT2): the total is within the limit of the submitter (or, for an order raised by splitting
     * a production order, of whoever split it - rule 6), so nobody else decides.
     * The submitter is recorded as the approver and the order is flagged {@link #autoApproved()}, which is what lets
     * {@code ck_purchase_orders_four_eyes} stand aside for exactly this case. Above the limit the order is
     * {@link #submit submitted} to PENDING_APPROVAL instead.
     */
    public void submitWithinLimit(UUID userId, UUID revisionId, long revisionNo, Money limit, Instant now) {
        if (status != PurchaseOrderStatus.DRAFT) {   // PENDING_APPROVAL → APPROVED is approve(), not a submission
            throw new InvalidPurchaseOrderTransitionException(id, status, PurchaseOrderStatus.APPROVED);
        }
        if (!coveredBy(limit)) {
            throw new BusinessException(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED,
                    "Purchase order %s totals %s, above the %s limit; it needs someone else's approval"
                            .formatted(poNumber, totalAmount(), limit));
        }
        this.status = PurchaseOrderStatus.APPROVED;
        this.submittedBy = Objects.requireNonNull(userId, "userId");
        this.submittedAt = Objects.requireNonNull(now, "now");
        this.approvedBy = userId;
        this.approvedAt = now;
        this.autoApproved = true;
        this.activeRevisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.pendingRevisionId = null;
        this.revisionNo = revisionNo;
    }

    /**
     * PENDING_APPROVAL → APPROVED. BR-PO-002 four-eyes: the approver did not submit it. And the approver's own limit
     * covers the total (owner decision POLIMIT2): an order above it waits for someone with a bigger one. An approver
     * with no limit configured is bounded by the approve permission alone (owner decision PO-NOLIMIT).
     *
     * @param approverLimit the highest approval limit the approver holds in this order's currency, or {@code null}
     *                      when none is configured for them
     */
    public void approve(UUID approverId, Money approverLimit, Instant now) {
        if (status != PurchaseOrderStatus.PENDING_APPROVAL) {
            // DRAFT → APPROVED is legal only as submitWithinLimit; approving a draft by hand is not.
            throw new InvalidPurchaseOrderTransitionException(id, status, PurchaseOrderStatus.APPROVED);
        }
        Objects.requireNonNull(approverId, "approverId");
        if (approverId.equals(submittedBy)) {
            throw new BusinessException(ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                    "A purchase order is approved by someone other than who submitted it");
        }
        if (approverLimit != null && !coveredBy(approverLimit)) {
            throw new BusinessException(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED,
                    "Purchase order %s totals %s; your approval limit is %s".formatted(poNumber, totalAmount(),
                            approverLimit));
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

    /**
     * → CLOSED with a reason, the order not to be received any more (docs 02 §4 step 14, §5). From PARTIALLY_RECEIVED
     * the open remainder is written off ({@link CloseKind#SHORT_CLOSE}); from CONFIRMED nothing was received at all,
     * and it is a force close ({@link CloseKind#FORCE_CLOSE}). Either way the reason is mandatory.
     */
    public void closeShort(String reason, UUID userId, Instant now) {
        CloseKind kind = switch (status) {
            case PARTIALLY_RECEIVED -> CloseKind.SHORT_CLOSE;
            case CONFIRMED -> CloseKind.FORCE_CLOSE;
            default -> throw new InvalidPurchaseOrderTransitionException(id, status, PurchaseOrderStatus.CLOSED);
        };
        requireReason(reason, 255);
        close(kind, reason.trim(), userId, now);
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

    /** Over the lines {@link #stillOrdered still ordered}: what an amendment removed is no longer paid for. */
    public Money subtotal() {
        return new Money(lines.stream().filter(this::stillOrdered).map(PoLine::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add), currency);
    }

    public Money taxTotal() {
        return new Money(lines.stream().filter(this::stillOrdered).map(PoLine::tax)
                .reduce(BigDecimal.ZERO, BigDecimal::add), currency);
    }

    public Money totalAmount() {
        return subtotal().plus(taxTotal());
    }

    /**
     * Whether a line is still part of what is ordered. A line comes to the aggregate unless it was WITHDRAWN from a
     * draft. A line an approved amendment removed (SCRUM-117) is not: no longer counted, and not a line a later
     * amendment can keep or remove again. A cancelled order keeps the total it had when it was cancelled, so the lines
     * its cancellation settled still count there - but not one an amendment had removed before.
     *
     * <p>Two signs, because neither covers every moment. In an order that is not cancelled, CANCELLED alone means an
     * amendment removed the line; that holds even while an approval runs, before the order points at the new
     * revision. Once the order is cancelled every line is CANCELLED, and only {@link PoLine#removedByAmendment}, read
     * from the revision the line still points at, tells them apart.</p>
     */
    private boolean stillOrdered(PoLine line) {
        if (line.status() != PoLineStatus.CANCELLED) {
            return true;
        }
        return status == PurchaseOrderStatus.CANCELLED && !line.removedByAmendment();
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
    public boolean autoApproved() { return autoApproved; }
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

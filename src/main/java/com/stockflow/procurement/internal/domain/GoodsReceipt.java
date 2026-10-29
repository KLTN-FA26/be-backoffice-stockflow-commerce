package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A goods receipt against one purchase order (docs 03, SCRUM-435): what arrived, counted, then for
 * the lines that need it, moved to the QC area and decided.
 *
 * <pre>
 *   DRAFT ──confirm──▶ IN_QC ──every QC line decided──▶ IN_PUTAWAY ──(putaway, SCRUM-92)──▶ CLOSED
 *     │ │      └──────(no QC line)────────────────────────▲
 *     │ └──cancel──▶ CANCELLED            IN_QC ──nothing left to put away──▶ CLOSED
 *     │
 *     └──confirm, count beyond a PO line's tolerance──▶ PENDING_APPROVAL ──manager approves / rejects the excess──▶ (as confirm)
 *                                                          └──cancel──▶ CANCELLED
 * </pre>
 *
 * <p>SCRUM-477 (BR-RCP-001): a count beyond the over-receipt tolerance is not refused, and not posted either. It waits
 * in PENDING_APPROVAL, counted in nowhere, until a user other than the receiver decides: <em>approve</em> posts the
 * whole count; <em>reject the excess</em> cuts the lines back to the tolerance and posts what is left (a receipt with
 * nothing left is cancelled). The decision, its maker and its reason stay on the receipt ({@link ReceiptApproval}).</p>
 *
 * <p>What the aggregate decides: the order of the steps, that a confirmed count is never edited
 * (BR-05), that QC decides only on goods already in the QC area and accounts for every unit of them
 * (BR-08). What it cannot see, and the service checks against the purchase order, the item and the
 * map: BR-01 (the PO is receivable), BR-02 (tolerance), BR-03 (lot and expiry), and the kind of area
 * each location is. The database states the same rules again as triggers.</p>
 *
 * <p>One receipt covers one purchase order, because {@code goods_receipts.po_id} says so. A truck
 * carrying several orders of one supplier (docs 03 edge cases) is received as one receipt per order.</p>
 */
public final class GoodsReceipt extends AggregateRoot {

    private final UUID id;
    private final String number;
    private final UUID purchaseOrderId;
    private final UUID purchaseOrderRevisionId;
    private final UUID warehouseId;
    private final UUID supplierId;
    private final String blindReason;
    private UUID referencedPurchaseOrderId;
    private final Instant receivedAt;
    private final UUID receivedBy;
    private final String deliveryNote;
    private final String note;
    private final List<ReceiptLine> lines;

    private GoodsReceiptStatus status;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private Instant closedAt;
    private ReceiptApproval approval;
    private final long version;

    /**
     * {@code purchaseOrderId} and {@code purchaseOrderRevisionId} are both null on a blind receipt (SCRUM-478), which
     * then names its {@code supplierId} and {@code blindReason} instead; {@code referencedPurchaseOrderId} is the PO
     * attached to it afterwards, a reference only.
     */
    public GoodsReceipt(UUID id, String number, UUID purchaseOrderId, UUID purchaseOrderRevisionId, UUID warehouseId,
                        UUID supplierId, String blindReason, UUID referencedPurchaseOrderId,
                        Instant receivedAt, UUID receivedBy, String deliveryNote, String note, List<ReceiptLine> lines,
                        GoodsReceiptStatus status, Instant confirmedAt, UUID confirmedBy, Instant closedAt,
                        ReceiptApproval approval, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        if ((purchaseOrderId == null) != (purchaseOrderRevisionId == null)) {
            throw new IllegalArgumentException("A receipt has a PO and its revision, or neither");
        }
        this.purchaseOrderId = purchaseOrderId;
        this.purchaseOrderRevisionId = purchaseOrderRevisionId;
        if (purchaseOrderId == null) {
            Objects.requireNonNull(supplierId, "supplierId");
            if (blindReason == null || blindReason.isBlank()) {
                throw new IllegalArgumentException("A receipt without a PO has a reason");
            }
        } else if (supplierId != null || blindReason != null || referencedPurchaseOrderId != null) {
            throw new IllegalArgumentException("A receipt against a PO has no blind supplier, reason or reference");
        }
        this.supplierId = supplierId;
        this.blindReason = blindReason;
        this.referencedPurchaseOrderId = referencedPurchaseOrderId;
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.receivedBy = Objects.requireNonNull(receivedBy, "receivedBy");
        this.deliveryNote = deliveryNote;
        this.note = note;
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
        this.status = Objects.requireNonNull(status, "status");
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
        this.closedAt = closedAt;
        this.approval = approval;
        this.version = version;
        requireDistinctLines(this.lines);
    }

    public static GoodsReceipt draft(UUID id, String number, UUID purchaseOrderId, UUID purchaseOrderRevisionId,
                                     UUID warehouseId, UUID receivedBy, String deliveryNote, String note,
                                     Instant now) {
        return new GoodsReceipt(id, number, Objects.requireNonNull(purchaseOrderId, "purchaseOrderId"),
                Objects.requireNonNull(purchaseOrderRevisionId, "purchaseOrderRevisionId"), warehouseId, null, null,
                null, now, receivedBy, deliveryNote, note, List.of(), GoodsReceiptStatus.DRAFT, null, null, null, null,
                0L);
    }

    /**
     * SCRUM-478, BR-RCP-002 (kltn-docs 03 edge cases): goods that arrived with no PO. The supplier and the reason are
     * required; it can be posted only with the warehouse manager's approval.
     */
    public static GoodsReceipt blindDraft(UUID id, String number, UUID supplierId, UUID warehouseId, UUID receivedBy,
                                          String reason, String deliveryNote, String note, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BLIND_RECEIPT_REASON_REQUIRED,
                    "A receipt without a purchase order needs a reason (BR-RCP-002)");
        }
        return new GoodsReceipt(id, number, null, null, warehouseId, Objects.requireNonNull(supplierId, "supplierId"),
                reason.strip(), null, now, receivedBy, deliveryNote, note, List.of(), GoodsReceiptStatus.DRAFT, null,
                null, null, null, 0L);
    }

    /** True for a receipt with no purchase order (BR-RCP-002). */
    public boolean blind() {
        return purchaseOrderId == null;
    }

    // ------------------------------------------------------------------ counting

    /** The whole count, replaced: a draft is a worksheet, edited until it is confirmed. */
    public void replaceLines(List<ReceiptLine> counted) {
        requireStatus(GoodsReceiptStatus.DRAFT, "change the lines of");
        requireDistinctLines(counted);
        lines.clear();
        lines.addAll(counted);
    }

    /**
     * The count is final (docs 03 step 6). The receipt goes on to QC when any line needs it, to
     * putaway otherwise. The caller counts the goods in as INBOUND stock in the same transaction.
     */
    public void confirm(UUID userId, Instant now) {
        confirm(userId, now, false);
    }

    /**
     * {@code handedToProduction}: the receipt of a SUBCONTRACT order, whose goods the production module takes over
     * in the same transaction for its single QC against the approved sample (user decision 2026-10-13). Receiving
     * has nothing left to do with them — no QC, no putaway — so the receipt is CLOSED as it is confirmed.
     */
    public void confirm(UUID userId, Instant now, boolean handedToProduction) {
        requireStatus(GoodsReceiptStatus.DRAFT, "confirm");
        if (blind()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Receipt %s has no purchase order: it is posted only after the warehouse manager approves it (BR-RCP-002)"
                            .formatted(number));
        }
        post(userId, now, handedToProduction);
    }

    /**
     * BR-RCP-001: the count goes beyond a PO line's tolerance, so it is not counted in. It waits for a user who may
     * decide it (never the one who counted it or the one asking now) and nothing downstream sees it until then.
     */
    public void requestApproval(UUID userId, Instant now) {
        requireStatus(GoodsReceiptStatus.DRAFT, "send for approval");
        requireLines();
        this.approval = ReceiptApproval.requested(Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(now, "now"));
        this.status = GoodsReceiptStatus.PENDING_APPROVAL;
    }

    /** The manager accepts the whole count; it is posted exactly as {@link #confirm} posts a count. */
    public void approve(UUID approverId, String reason, Instant now, boolean handedToProduction) {
        requireDecidable(approverId, reason);
        this.approval = approval.decided(ReceiptApproval.Decision.APPROVED, approverId, now, reason.strip());
        post(approverId, now, handedToProduction);
    }

    /**
     * The manager refuses the part beyond the tolerance: the lines are cut back to {@code allowedByPoLine} (units each
     * PO line may still receive on this receipt) first, by {@link #cutBackTo}, then this posts what is left. With
     * nothing left the receipt is cancelled: nothing was received that the tolerance allows.
     */
    public void rejectExcess(UUID approverId, String reason, Instant now, boolean handedToProduction) {
        requireDecidable(approverId, reason);
        requirePurchaseOrder("reject the excess of");
        this.approval = approval.decided(ReceiptApproval.Decision.EXCESS_REJECTED, approverId, now, reason.strip());
        if (lines.isEmpty()) {
            this.status = GoodsReceiptStatus.CANCELLED;
            return;
        }
        post(approverId, now, handedToProduction);
    }

    /**
     * Cuts each named PO line's units on this receipt down to what it may still receive: the excess comes off the
     * lines of that PO line from the last one counted backwards, a line left with nothing is removed. Only a receipt
     * waiting for approval is cut, and only by the rejection of its excess.
     */
    public void cutBackTo(Map<UUID, Integer> allowedByPoLine) {
        requireStatus(GoodsReceiptStatus.PENDING_APPROVAL, "cut back");
        requirePurchaseOrder("cut back");
        List<ReceiptLine> kept = new ArrayList<>();
        Map<UUID, Integer> toCut = new java.util.HashMap<>();
        for (Map.Entry<UUID, Integer> allowed : allowedByPoLine.entrySet()) {
            toCut.put(allowed.getKey(), Math.max(quantityOf(allowed.getKey()) - Math.max(allowed.getValue(), 0), 0));
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            ReceiptLine line = lines.get(i);
            int cut = Math.min(toCut.getOrDefault(line.poLineId(), 0), line.receivedQuantity());
            toCut.computeIfPresent(line.poLineId(), (k, v) -> v - cut);
            if (cut < line.receivedQuantity()) {
                kept.add(0, cut == 0 ? line : line.withReceivedQuantity(line.receivedQuantity() - cut));
            }
        }
        lines.clear();
        lines.addAll(kept);
    }

    /**
     * BR-RCP-002: the manager refuses a receipt without a purchase order: the whole receipt is cancelled, with the
     * decision, who made it and why on it. (Rejecting only the excess is for a receipt against a PO.)
     */
    public void reject(UUID approverId, String reason, Instant now) {
        requireDecidable(approverId, reason);
        if (!blind()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Receipt %s is against a purchase order: its excess is rejected, the receipt is not".formatted(number));
        }
        this.approval = approval.decided(ReceiptApproval.Decision.REJECTED, approverId, now, reason.strip());
        this.status = GoodsReceiptStatus.CANCELLED;
    }

    /**
     * SCRUM-478, owner decision NOPO-ATTACH: the PO a receipt without one turned out to belong to. A reference only:
     * it changes nothing about the PO and nothing is counted against it. The caller has checked that the PO exists;
     * it must be the receipt's supplier's ({@code RECEIPT_REFERENCE_SUPPLIER_MISMATCH}). Not for a cancelled receipt.
     */
    public void referencePurchaseOrder(UUID purchaseOrderId, UUID purchaseOrderSupplierId) {
        requirePurchaseOrderless("reference a purchase order on");
        if (status == GoodsReceiptStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Receipt %s is cancelled: no purchase order is attached to it".formatted(number));
        }
        if (!supplierId.equals(purchaseOrderSupplierId)) {
            throw new BusinessException(ErrorCode.RECEIPT_REFERENCE_SUPPLIER_MISMATCH,
                    "The purchase order is not a purchase order of the supplier of receipt %s".formatted(number));
        }
        this.referencedPurchaseOrderId = Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
    }

    private void requirePurchaseOrder(String verb) {
        if (blind()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Cannot %s receipt %s: it has no purchase order".formatted(verb, number));
        }
    }

    private void requirePurchaseOrderless(String verb) {
        if (!blind()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Cannot %s receipt %s: it is against a purchase order".formatted(verb, number));
        }
    }

    private void post(UUID userId, Instant now, boolean handedToProduction) {
        requireLines();
        this.confirmedBy = Objects.requireNonNull(userId, "userId");
        this.confirmedAt = Objects.requireNonNull(now, "now");
        if (handedToProduction) {
            if (lines.stream().anyMatch(ReceiptLine::qcRequired)) {
                throw new IllegalStateException("A receipt handed to production has no receiving QC of its own");
            }
            this.status = GoodsReceiptStatus.CLOSED;
            this.closedAt = now;
            return;
        }
        this.status = lines.stream().anyMatch(ReceiptLine::qcRequired)
                ? GoodsReceiptStatus.IN_QC : GoodsReceiptStatus.IN_PUTAWAY;
    }

    private void requireLines() {
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Receipt %s has no lines to confirm".formatted(number));
        }
    }

    /** Waiting for a decision, a reason given, and a decider who is neither the receiver nor the one who asked. */
    private void requireDecidable(UUID approverId, String reason) {
        requireStatus(GoodsReceiptStatus.PENDING_APPROVAL, "decide");
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED,
                    "Deciding on receipt %s needs a reason".formatted(number));
        }
        Objects.requireNonNull(approverId, "approverId");
        if (approverId.equals(receivedBy) || approverId.equals(approval.requestedBy())) {
            throw new BusinessException(ErrorCode.RECEIPT_SELF_APPROVAL,
                    "Receipt %s cannot be decided by the person who counted it or asked for its approval"
                            .formatted(number));
        }
    }

    /** Only before the count is posted: once counted in, a receipt is corrected by an adjustment or a reversal (BR-05). */
    public void cancel() {
        if (status != GoodsReceiptStatus.DRAFT && status != GoodsReceiptStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Cannot cancel receipt %s: it is %s, not DRAFT or PENDING_APPROVAL".formatted(number, status));
        }
        this.status = GoodsReceiptStatus.CANCELLED;
    }

    // ------------------------------------------------------------------ QC

    /** Docs 03 step 7: the goods of a 3-step line are now in the QC area. */
    public ReceiptLine moveToQc(UUID lineId, UUID qcLocationId, UUID userId, Instant now) {
        requireStatus(GoodsReceiptStatus.IN_QC, "move goods to QC on");
        ReceiptLine line = line(lineId);
        if (!line.qcRequired()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "This line needs no QC; it goes straight to putaway (2-step flow)");
        }
        if (line.movedToQc()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "The goods of this line are already in the QC area");
        }
        line.markMovedToQc(qcLocationId, userId, now);
        return line;
    }

    /**
     * Docs 03 steps 8–9. Every unit moved to QC gets exactly one outcome (BR-08), and quarantined or
     * rejected units a reason. When this was the last undecided line, the receipt moves on: to putaway
     * if anything is waiting for it, straight to CLOSED if nothing is.
     */
    public void inspect(UUID lineId, List<QcInspection> decisions, Instant now) {
        requireStatus(GoodsReceiptStatus.IN_QC, "record QC on");
        ReceiptLine line = line(lineId);
        if (!line.movedToQc()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "QC decides only on goods already moved to the QC area (BR-08)");
        }
        if (line.inspected()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION, "This line has already been inspected");
        }
        int total = decisions.stream().mapToInt(QcInspection::quantity).sum();
        if (total != line.receivedQuantity()) {
            throw new BusinessException(ErrorCode.QC_QUANTITY_MISMATCH,
                    "Accepted, quarantined and rejected add up to %d; %d units were moved to QC"
                            .formatted(total, line.receivedQuantity()));
        }
        for (QcInspection decision : decisions) {
            if (decision.outcome() != QcOutcome.ACCEPTED
                    && (decision.reason() == null || decision.reason().isBlank())) {
                throw new BusinessException(ErrorCode.QC_REASON_REQUIRED,
                        "A %s decision needs a reason".formatted(decision.outcome()));
            }
            if (decision.outcome() != QcOutcome.ACCEPTED && decision.targetLocationId() == null) {
                throw new BusinessException(ErrorCode.QC_TARGET_LOCATION_REQUIRED,
                        "A %s decision needs the quarantine location the goods go to".formatted(decision.outcome()));
            }
        }
        line.record(decisions);
        if (lines.stream().filter(ReceiptLine::qcRequired).allMatch(ReceiptLine::inspected)) {
            boolean anythingToPutAway = lines.stream().anyMatch(l -> l.quantityForPutaway() > 0);
            if (anythingToPutAway) {
                this.status = GoodsReceiptStatus.IN_PUTAWAY;
            } else {
                this.status = GoodsReceiptStatus.CLOSED;
                this.closedAt = now;
            }
        }
    }

    /**
     * SCRUM-92: the receipt closes once every line has had all its goods put away.
     *
     * <p>Decided on quantities ({@code doneByLine}: units of DONE putaway tasks per line), not on "no open task is
     * left": tasks are opened asynchronously after the receipt event and, on a mixed receipt, a QC line gets its
     * tasks only after the inspection, so "no open task" is also true before the first task exists and between two
     * QC decisions. The quantity a line must reach is {@link ReceiptLine#quantityForPutaway()}; a line with nothing
     * to put away (everything rejected or quarantined by QC) never holds the receipt open.</p>
     *
     * @return true when the receipt is now CLOSED; false, and unchanged, when it is not in putaway or a line is short
     */
    public boolean closeIfPutAway(Map<UUID, Integer> doneByLine, Instant now) {
        if (status != GoodsReceiptStatus.IN_PUTAWAY) {
            return false;
        }
        boolean allPutAway = lines.stream().allMatch(l ->
                l.quantityForPutaway() <= 0 || doneByLine.getOrDefault(l.id(), 0) >= l.quantityForPutaway());
        if (!allPutAway) {
            return false;
        }
        this.status = GoodsReceiptStatus.CLOSED;
        this.closedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    // ------------------------------------------------------------------ queries

    public ReceiptLine line(UUID lineId) {
        return findLine(lineId).orElseThrow(() -> new BusinessException(ErrorCode.RECEIPT_LINE_NOT_FOUND,
                "Receipt %s has no line %s".formatted(number, lineId)));
    }

    public Optional<ReceiptLine> findLine(UUID lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst();
    }

    /** Units of one PO line on this receipt. */
    public int quantityOf(UUID poLineId) {
        return lines.stream().filter(l -> poLineId.equals(l.poLineId())).mapToInt(ReceiptLine::receivedQuantity).sum();
    }

    private void requireStatus(GoodsReceiptStatus expected, String verb) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Cannot %s receipt %s: it is %s, not %s".formatted(verb, number, status, expected));
        }
    }

    /** One line per PO line and lot, as {@code uk_goods_receipt_lines_lot} says. */
    private static void requireDistinctLines(List<ReceiptLine> candidate) {
        Set<String> keys = new HashSet<>();
        for (ReceiptLine line : candidate) {
            // One line per PO line and lot; a blind receipt has no PO line, so one per item and lot.
            String what = line.poLineId() == null ? "item " + line.inventoryItemId() : "PO line " + line.poLineId();
            if (!keys.add(what + "|" + Objects.toString(line.lotNumber(), ""))) {
                throw new BusinessException(ErrorCode.DUPLICATE_RECEIPT_LINE,
                        "%s%s appears twice; put the whole quantity of a lot on one line".formatted(
                                what, line.lotNumber() == null ? "" : " lot " + line.lotNumber()));
            }
        }
    }

    public UUID id() { return id; }
    public String number() { return number; }
    public UUID purchaseOrderId() { return purchaseOrderId; }
    public UUID purchaseOrderRevisionId() { return purchaseOrderRevisionId; }
    public UUID warehouseId() { return warehouseId; }
    /** The supplier of a receipt without a PO; null on a receipt against one (the PO names the supplier). */
    public UUID supplierId() { return supplierId; }
    /** Why goods arrived with no PO (BR-RCP-002); null on a receipt against a PO. */
    public String blindReason() { return blindReason; }
    /** The PO attached afterwards to a receipt without one; a reference only. */
    public UUID referencedPurchaseOrderId() { return referencedPurchaseOrderId; }
    public Instant receivedAt() { return receivedAt; }
    public UUID receivedBy() { return receivedBy; }
    public String deliveryNote() { return deliveryNote; }
    public String note() { return note; }
    public List<ReceiptLine> lines() { return Collections.unmodifiableList(lines); }
    public GoodsReceiptStatus status() { return status; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID confirmedBy() { return confirmedBy; }
    public Instant closedAt() { return closedAt; }
    /** The exception approval this receipt asked for; empty when it never went beyond a tolerance. */
    public Optional<ReceiptApproval> approval() { return Optional.ofNullable(approval); }
    public long version() { return version; }
}

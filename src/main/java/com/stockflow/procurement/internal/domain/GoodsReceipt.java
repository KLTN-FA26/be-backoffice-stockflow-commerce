package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
 *     │        └──────(no QC line)────────────────────────▲
 *     └──cancel──▶ CANCELLED            IN_QC ──nothing left to put away──▶ CLOSED
 * </pre>
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
    private final Instant receivedAt;
    private final UUID receivedBy;
    private final String deliveryNote;
    private final String note;
    private final List<ReceiptLine> lines;

    private GoodsReceiptStatus status;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private Instant closedAt;
    private final long version;

    public GoodsReceipt(UUID id, String number, UUID purchaseOrderId, UUID purchaseOrderRevisionId, UUID warehouseId,
                        Instant receivedAt, UUID receivedBy, String deliveryNote, String note, List<ReceiptLine> lines,
                        GoodsReceiptStatus status, Instant confirmedAt, UUID confirmedBy, Instant closedAt,
                        long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.purchaseOrderId = Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
        this.purchaseOrderRevisionId = Objects.requireNonNull(purchaseOrderRevisionId, "purchaseOrderRevisionId");
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
        this.version = version;
        requireDistinctLines(this.lines);
    }

    public static GoodsReceipt draft(UUID id, String number, UUID purchaseOrderId, UUID purchaseOrderRevisionId,
                                     UUID warehouseId, UUID receivedBy, String deliveryNote, String note,
                                     Instant now) {
        return new GoodsReceipt(id, number, purchaseOrderId, purchaseOrderRevisionId, warehouseId, now, receivedBy,
                deliveryNote, note, List.of(), GoodsReceiptStatus.DRAFT, null, null, null, 0L);
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
        requireStatus(GoodsReceiptStatus.DRAFT, "confirm");
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Receipt %s has no lines to confirm".formatted(number));
        }
        this.confirmedBy = Objects.requireNonNull(userId, "userId");
        this.confirmedAt = Objects.requireNonNull(now, "now");
        this.status = lines.stream().anyMatch(ReceiptLine::qcRequired)
                ? GoodsReceiptStatus.IN_QC : GoodsReceiptStatus.IN_PUTAWAY;
    }

    /** Only a draft: once counted in, a receipt is corrected by an adjustment or a reversal (BR-05). */
    public void cancel() {
        requireStatus(GoodsReceiptStatus.DRAFT, "cancel");
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
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A %s decision needs a reason".formatted(decision.outcome()));
            }
            if (decision.outcome() != QcOutcome.ACCEPTED && decision.targetLocationId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
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

    // ------------------------------------------------------------------ queries

    public ReceiptLine line(UUID lineId) {
        return findLine(lineId).orElseThrow(() -> new BusinessException(ErrorCode.GOODS_RECEIPT_NOT_FOUND,
                "Receipt %s has no line %s".formatted(number, lineId)));
    }

    public Optional<ReceiptLine> findLine(UUID lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst();
    }

    /** Units of one PO line on this receipt. */
    public int quantityOf(UUID poLineId) {
        return lines.stream().filter(l -> l.poLineId().equals(poLineId)).mapToInt(ReceiptLine::receivedQuantity).sum();
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
            if (!keys.add(line.poLineId() + "|" + Objects.toString(line.lotNumber(), ""))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "PO line %s%s appears twice; put the whole quantity of a lot on one line".formatted(
                                line.poLineId(), line.lotNumber() == null ? "" : " lot " + line.lotNumber()));
            }
        }
    }

    public UUID id() { return id; }
    public String number() { return number; }
    public UUID purchaseOrderId() { return purchaseOrderId; }
    public UUID purchaseOrderRevisionId() { return purchaseOrderRevisionId; }
    public UUID warehouseId() { return warehouseId; }
    public Instant receivedAt() { return receivedAt; }
    public UUID receivedBy() { return receivedBy; }
    public String deliveryNote() { return deliveryNote; }
    public String note() { return note; }
    public List<ReceiptLine> lines() { return Collections.unmodifiableList(lines); }
    public GoodsReceiptStatus status() { return status; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID confirmedBy() { return confirmedBy; }
    public Instant closedAt() { return closedAt; }
    public long version() { return version; }
}

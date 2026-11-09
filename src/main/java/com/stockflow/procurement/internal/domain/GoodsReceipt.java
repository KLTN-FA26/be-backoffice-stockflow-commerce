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
 * A goods receipt against one production order (4A-5, kltn-docs 03 §4.1): the cartons a producer delivered, counted,
 * then put away. There is no quality check at the warehouse (kltn-docs 03 BR-07): a confirmed count goes straight on
 * to putaway.
 *
 * <pre>
 *   DRAFT ──confirm──▶ IN_PUTAWAY ──(putaway, SCRUM-92)──▶ CLOSED
 *     │
 *     └──cancel──▶ CANCELLED
 * </pre>
 *
 * <p>There is no tolerance: BR-02 refuses what is over instead, so a receipt never waits for the warehouse manager.
 * Its lines count production order lines, in whole cartons, in the production order's lot.</p>
 *
 * <p><b>Purchase receipts are history</b> (4A-8, purchasing removed: kltn-docs 00 of 04/11). A receipt with no
 * production order ({@link GoodsReceiptType#PURCHASE}) was counted against a purchase order or none before 4A-5; it is
 * read as it stands and, while it has counted nothing in (DRAFT, or PENDING_APPROVAL from the old over-tolerance
 * approval), it may only be cancelled - nothing is counted or posted against a purchase order any more. Its purchase
 * order, supplier and approval columns are not read: 4A-9 archives and drops them.</p>
 *
 * <p>What the aggregate decides: the order of the steps, and that a confirmed count is never edited (BR-05). What it
 * cannot see, and the service checks against the production order, the item and the map: BR-01, BR-02, BR-13 and the
 * kind of area each location is. The database states the same rules again as triggers.</p>
 */
public final class GoodsReceipt extends AggregateRoot {

    private final UUID id;
    private final String number;
    private final UUID productionOrderId;
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

    /**
     * {@code productionOrderId} names the production order of a PRODUCTION receipt; null on a historical purchase
     * receipt.
     */
    public GoodsReceipt(UUID id, String number, UUID productionOrderId, UUID warehouseId, Instant receivedAt,
                        UUID receivedBy, String deliveryNote, String note, List<ReceiptLine> lines,
                        GoodsReceiptStatus status, Instant confirmedAt, UUID confirmedBy, Instant closedAt,
                        long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.productionOrderId = productionOrderId;
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.receivedBy = Objects.requireNonNull(receivedBy, "receivedBy");
        this.deliveryNote = deliveryNote;
        this.note = note;
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
        this.status = Objects.requireNonNull(status, "status");
        if (productionOrderId != null && status == GoodsReceiptStatus.PENDING_APPROVAL) {
            throw new IllegalArgumentException("A receipt against a production order never waits for approval");
        }
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
        this.closedAt = closedAt;
        this.version = version;
        requireLinesOfItsKind(this.lines);
        if (productionOrderId != null) {
            requireDistinctLines(this.lines);
        }
    }

    /**
     * 4A-5 (kltn-docs 03 §4.1): a DRAFT against one production order, at the warehouse it is received at. The caller has
     * checked that goods can be received against it (BR-01, BR-15).
     */
    public static GoodsReceipt productionDraft(UUID id, String number, UUID productionOrderId, UUID warehouseId,
                                               UUID receivedBy, String deliveryNote, String note, Instant now) {
        return new GoodsReceipt(id, number, Objects.requireNonNull(productionOrderId, "productionOrderId"),
                warehouseId, now, receivedBy, deliveryNote, note, List.of(), GoodsReceiptStatus.DRAFT, null, null,
                null, 0L);
    }

    /** True for a receipt against a production order (4A-5); false for a historical purchase receipt. */
    public boolean againstProductionOrder() {
        return productionOrderId != null;
    }

    public GoodsReceiptType type() {
        return productionOrderId != null ? GoodsReceiptType.PRODUCTION : GoodsReceiptType.PURCHASE;
    }

    /**
     * A draft still: its count may be replaced. For a caller that checks the receipt's own state before the rules it
     * must look elsewhere for, so the answer names the receipt rather than a rule it could not have broken.
     */
    public void requireDraft(String verb) {
        requireStatus(GoodsReceiptStatus.DRAFT, verb);
    }

    // ------------------------------------------------------------------ counting

    /** The whole count, replaced: a draft is a worksheet, edited until it is confirmed. */
    public void replaceLines(List<ReceiptLine> counted) {
        requireProductionOrder("change the lines of");
        requireStatus(GoodsReceiptStatus.DRAFT, "change the lines of");
        requireDistinctLines(counted);
        requireLinesOfItsKind(counted);
        lines.clear();
        lines.addAll(counted);
    }

    /**
     * The count is final (docs 03 step 6). The receipt goes on to putaway; the caller counts the goods in as INBOUND
     * stock in the same transaction.
     */
    public void confirm(UUID userId, Instant now) {
        requireProductionOrder("confirm");
        requireStatus(GoodsReceiptStatus.DRAFT, "confirm");
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Receipt %s has no lines to confirm".formatted(number));
        }
        this.confirmedBy = Objects.requireNonNull(userId, "userId");
        this.confirmedAt = Objects.requireNonNull(now, "now");
        this.status = GoodsReceiptStatus.IN_PUTAWAY;
    }

    /**
     * Only before the count is posted: once counted in, a receipt is corrected by an adjustment or a reversal (BR-05).
     * A historical purchase receipt that never counted anything in - a draft, or one left waiting for the old
     * over-tolerance approval - is cancelled the same way: it is the only way it can still move.
     */
    public void cancel() {
        if (status != GoodsReceiptStatus.DRAFT && status != GoodsReceiptStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Cannot cancel receipt %s: it is %s, not DRAFT or PENDING_APPROVAL".formatted(number, status));
        }
        this.status = GoodsReceiptStatus.CANCELLED;
    }

    /**
     * SCRUM-92: the receipt closes once every line has had all its goods put away.
     *
     * <p>Decided on quantities ({@code doneByLine}: units of DONE putaway tasks per line), not on "no open task is
     * left": tasks are opened asynchronously after the receipt event, so "no open task" is also true before the first
     * task exists. Every unit a line received is put away from it.</p>
     *
     * @return true when the receipt is now CLOSED; false, and unchanged, when it is not in putaway or a line is short
     */
    public boolean closeIfPutAway(Map<UUID, Integer> doneByLine, Instant now) {
        if (status != GoodsReceiptStatus.IN_PUTAWAY) {
            return false;
        }
        boolean allPutAway = lines.stream().allMatch(l ->
                doneByLine.getOrDefault(l.id(), 0) >= l.receivedQuantity());
        if (!allPutAway) {
            return false;
        }
        this.status = GoodsReceiptStatus.CLOSED;
        this.closedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    /**
     * Nothing is counted or posted against a purchase order any more (4A-8): a historical purchase receipt is read, or
     * cancelled while it has counted nothing in.
     */
    private void requireProductionOrder(String verb) {
        if (productionOrderId == null) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION, ("Cannot %s receipt %s: it was counted "
                    + "against a purchase order, and purchasing is gone; such a receipt is only cancelled")
                    .formatted(verb, number));
        }
    }

    // ------------------------------------------------------------------ queries

    public ReceiptLine line(UUID lineId) {
        return findLine(lineId).orElseThrow(() -> new BusinessException(ErrorCode.RECEIPT_LINE_NOT_FOUND,
                "Receipt %s has no line %s".formatted(number, lineId)));
    }

    public Optional<ReceiptLine> findLine(UUID lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst();
    }

    private void requireStatus(GoodsReceiptStatus expected, String verb) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Cannot %s receipt %s: it is %s, not %s".formatted(verb, number, status, expected));
        }
    }

    /** One line per production order line ({@code uk_goods_receipt_lines_production_order_line}). */
    private static void requireDistinctLines(List<ReceiptLine> candidate) {
        Set<UUID> keys = new HashSet<>();
        for (ReceiptLine line : candidate) {
            if (!keys.add(line.productionOrderLineId())) {
                throw new BusinessException(ErrorCode.DUPLICATE_RECEIPT_LINE, ("Production order line %s appears "
                        + "twice; put all its cartons on one line").formatted(line.productionOrderLineId()));
            }
        }
    }

    /** A line counts a production order line exactly when its receipt is against a production order (4A-5). */
    private void requireLinesOfItsKind(List<ReceiptLine> candidate) {
        for (ReceiptLine line : candidate) {
            if ((line.productionOrderLineId() != null) != againstProductionOrder()) {
                throw new IllegalArgumentException("A line counts a production order line exactly when its receipt is"
                        + " against a production order");
            }
        }
    }

    public UUID id() { return id; }
    public String number() { return number; }
    /** The production order a PRODUCTION receipt is against (4A-5); null on a historical purchase receipt. */
    public UUID productionOrderId() { return productionOrderId; }
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

package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.ReceiptApproval;
import com.stockflow.procurement.internal.domain.QcOutcome;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Goods receipts against purchase orders (SCRUM-435, docs 03): count, confirm, move to QC, decide.
 *
 * <p>Not on {@code procurement :: api}: nothing outside this module drives a receipt. Other modules
 * learn about receipts from the {@code GoodsReceiptConfirmed} and {@code ReceiptStockReadyForPutaway}
 * events.</p>
 */
public interface GoodsReceipts {

    /**
     * Warning (SCRUM-477, BR-RCP-001) on a line, and on the receipt, of a count not yet posted that goes beyond the
     * supplier's over-receipt tolerance on its PO line: confirming it sends the receipt to the warehouse manager.
     */
    String OVER_TOLERANCE = "OVER_TOLERANCE";

    record Create(UUID purchaseOrderId, String deliveryNote, String note) {
    }

    /**
     * One counted line: so many units of a PO line, from one lot, set down at a RECEIVING location (SCRUM-473 design
     * §3). {@code quantity} is the line's total in base units. {@code packages} are the labelled cartons holding part
     * or all of it, the whole list each time; then {@code unitOfMeasure} is the active packing unit they are in. With
     * no cartons the line is loose and {@code unitOfMeasure} is absent or the item's base unit.
     */
    record NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate,
                   String locationCode, String note, String unitOfMeasure, List<NewPackage> packages) {

        public NewLine {
            packages = packages == null ? List.of() : List.copyOf(packages);
        }

        /** A loose line, counted in the base unit. */
        public NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate,
                       String locationCode, String note) {
            this(purchaseOrderLineId, quantity, lotNumber, expiryDate, locationCode, note, null, List.of());
        }
    }

    /** One carton of a line: {@code lpn} names one labelled before to recount it; null labels a new one. */
    record NewPackage(String lpn, int quantity) {
    }

    /**
     * Part of a QC decision that leaves the QC area: where it goes and why. {@code quantity} is the loose units of the
     * part; {@code packages} the LPNs of the line's cartons that go whole (SCRUM-473 design §5), each counted at what
     * was counted into it. The part is its loose units plus those cartons.
     */
    record Part(int quantity, String locationCode, String reason, List<String> packages) {

        public Part {
            packages = packages == null ? List.of() : List.copyOf(packages);
        }

        /** A part of loose units only. */
        public Part(int quantity, String locationCode, String reason) {
            this(quantity, locationCode, reason, List.of());
        }

        /** Whether the part decides anything: a part of no units and no cartons is not recorded. */
        public boolean hasGoods() {
            return quantity > 0 || !packages.isEmpty();
        }
    }

    /**
     * A QC decision on a whole line. {@code quarantined} and {@code rejected} may be null. {@code accepted} is a
     * number of units: the cartons no part names, and the loose units no part takes.
     */
    record Decision(int accepted, Part quarantined, Part rejected) {
    }

    /** Where a line is in the 3-step flow (docs 03 §5.2). */
    /**
     * {@code PRODUCTION_QC}: a SUBCONTRACT order's goods, which the production module holds for its production order
     * and inspects once against the approved sample — receiving runs no QC on them and puts none of them away.
     */
    enum QcProgress { NOT_REQUIRED, AWAITING_MOVE_TO_QC, IN_QC_AREA, INSPECTED, PRODUCTION_QC }

    record InspectionView(UUID id, QcOutcome outcome, int quantity, String locationCode, String reason,
                          UUID inspectedBy, String inspectedByName, Instant inspectedAt) {
    }

    /**
     * {@code unitOfMeasure}: the packing unit of the line's cartons, null for a line counted loose.
     * {@code looseQuantity}: what was counted into no carton, {@code quantity} less what the cartons were counted at
     * ({@code receivedQty}); it stays as counted once the receipt is confirmed, whatever leaves the cartons later. {@code packages}: the
     * line's cartons, voided ones left out, by LPN - the client prints their labels from these. {@code units}: every
     * unit of the line's item, so the counting screen can offer the packing units without the product permission
     * the unit catalogue sits behind.
     */
    record LineView(UUID id, UUID purchaseOrderLineId, Integer purchaseOrderLineNo, UUID inventoryItemId, String sku,
                    int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note,
                    boolean qcRequired, boolean lotTracked, boolean expiryTracked, QcProgress qcProgress,
                    String qcLocationCode, Instant movedToQcAt, int quantityForPutaway,
                    List<InspectionView> inspections, List<String> warnings, String unitOfMeasure,
                    int looseQuantity, List<PackageView> packages, List<UnitView> units,
                    String orderedUnitOfMeasure) {
    }

    /**
     * A unit the line's item can be counted in, the base unit first, then by code. Cartons take an {@code active}
     * unit that is not {@code base}; {@code factor} is what one of them holds; {@code defaultPurchase} marks the unit
     * the item is bought in, the natural default for a new line.
     */
    record UnitView(String unitOfMeasure, int factor, boolean base, boolean active, boolean defaultPurchase) {
    }

    /**
     * One carton of a line. {@code quantity}: what is in it now - what was counted into it while RECEIVING, what is
     * left in it once in stock. {@code receivedQty}: what was counted into it, which the receipt's
     * {@code looseQuantity} is reckoned from and which never changes once the carton is in stock. {@code nominalQty}:
     * what its unit holds. {@code status}: RECEIVING, IN_STOCK, CONSUMED or LOST. {@code locationCode}: where it
     * stands now, so a carton that went to quarantine is found there.
     */
    record PackageView(UUID id, String lpn, int quantity, int receivedQty, int nominalQty, String status,
                       String locationCode) {
    }

    /**
     * The manager's exception approval of a receipt that went beyond a PO line's tolerance (SCRUM-477): who asked
     * and when, and once decided what (APPROVED: the whole count; EXCESS_REJECTED: cut back to the tolerance), who,
     * when and why. {@code decision} and what follows it are null while the receipt waits.
     */
    record Approval(Instant requestedAt, UUID requestedBy, String requestedByName, ReceiptApproval.Decision decision,
                    Instant decidedAt, UUID decidedBy, String decidedByName, String reason) {
    }

    /**
     * The names next to the ids are for display (SCRUM-464); a name is null when its row is gone.
     * {@code warnings} are non-blocking business warnings: {@code EXPIRY_NEAR} (docs 03 BR-06) when a line's
     * expiry is within the configured window of the receiving date, empty on a cancelled receipt; and
     * {@link #OVER_TOLERANCE} (BR-RCP-001) on a count not yet posted that goes beyond a tolerance. {@code approval}:
     * null for a receipt that never needed the manager.
     */
    record ReceiptView(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
                       String supplierName, UUID warehouseId, String warehouseCode, String warehouseName,
                       GoodsReceiptStatus status, String deliveryNote, String note, Instant receivedAt,
                       UUID receivedBy, String receivedByName, Instant confirmedAt, UUID confirmedBy,
                       String confirmedByName, Instant closedAt, List<LineView> lines, List<String> warnings,
                       Approval approval) {
    }

    record Row(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
               String supplierName, UUID warehouseId, String warehouseCode, String warehouseName,
               GoodsReceiptStatus status, String deliveryNote, Instant receivedAt, UUID receivedBy,
               String receivedByName, Instant confirmedAt, UUID confirmedBy, String confirmedByName,
               Instant closedAt) {
    }

    /** A DRAFT receipt for a CONFIRMED or PARTIALLY_RECEIVED order (BR-01), into the order's warehouse. */
    ReceiptView create(Create command, UUID userId);

    /**
     * Replace the whole count of a DRAFT (BR-03 lot and expiry, RECEIVING locations), and label, recount or void each
     * line's cartons to match it (SCRUM-473). A count beyond the tolerance (BR-02) is flagged, not refused (SCRUM-477).
     */
    ReceiptView replaceLines(UUID receiptId, List<NewLine> lines);

    /**
     * The count is final: the goods are counted in as INBOUND stock at their receiving locations - into the line's
     * labelled cartons first, the rest loose - the order's progress is updated, and the receipt goes to QC or to
     * putaway.
     *
     * <p>When a PO line would go beyond the supplier's over-receipt tolerance (BR-02, BR-RCP-001) nothing is counted
     * in: the receipt goes to PENDING_APPROVAL (SCRUM-477) and waits for {@link #approve} or {@link #rejectExcess}.</p>
     */
    ReceiptView confirm(UUID receiptId, UUID userId);

    /**
     * BR-RCP-001: the warehouse manager accepts the whole count of a receipt waiting in PENDING_APPROVAL, beyond the
     * tolerance. It is posted exactly as {@link #confirm} posts a count, by and in the name of the approver.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_RECEIPT_TRANSITION} when the receipt is not
     *         waiting; {@code RECEIPT_APPROVAL_REASON_REQUIRED}; {@code RECEIPT_SELF_APPROVAL} when the approver
     *         counted the receipt or asked for its approval; {@code PURCHASE_ORDER_NOT_RECEIVABLE}
     */
    ReceiptView approve(UUID receiptId, UUID approverId, String reason);

    /**
     * BR-RCP-001: the warehouse manager refuses the part beyond the tolerance of a receipt waiting in
     * PENDING_APPROVAL. Each PO line keeps only what its limit still allows after the receipts posted so far (the
     * excess comes off the lines of that PO line from the last one counted), and the receipt is posted with that; a
     * receipt with nothing left is cancelled. Same refusals as {@link #approve}, and {@code RECEIPT_PACKAGES_EXCEED_LINE}
     * / {@code RECEIPT_LINE_HAS_PACKAGES} when labelled cartons stand in the way of the cut.
     */
    ReceiptView rejectExcess(UUID receiptId, UUID approverId, String reason);

    /** A DRAFT, or a receipt waiting for approval: neither has counted anything in (BR-05). */
    ReceiptView cancel(UUID receiptId);

    /**
     * Docs 03 step 7: a QC-required line's goods - its cartons whole, and its loose units - are now in a
     * QUALITY_CONTROL area.
     */
    ReceiptView moveToQc(UUID receiptId, UUID lineId, String qcLocationCode, UUID userId);

    /**
     * Docs 03 steps 8–9: accepted stays for putaway, quarantined and rejected go to QUARANTINE areas - loose units by
     * quantity, cartons whole by LPN (SCRUM-473).
     */
    ReceiptView inspect(UUID receiptId, UUID lineId, Decision decision, UUID userId);

    ReceiptView get(UUID receiptId);

    PageResponse<Row> list(List<GoodsReceiptStatus> statuses, UUID purchaseOrderId, UUID warehouseId, String search,
                           Instant receivedFrom, Instant receivedTo, int page, int size, String sort);
}

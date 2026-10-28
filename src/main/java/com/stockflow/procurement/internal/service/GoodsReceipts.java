package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
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
     * The names next to the ids are for display (SCRUM-464); a name is null when its row is gone.
     * {@code warnings} are non-blocking business warnings (docs 03 BR-06): {@code EXPIRY_NEAR} when a line's
     * expiry is within the configured window of the receiving date. Empty on a cancelled receipt.
     */
    record ReceiptView(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
                       String supplierName, UUID warehouseId, String warehouseCode, String warehouseName,
                       GoodsReceiptStatus status, String deliveryNote, String note, Instant receivedAt,
                       UUID receivedBy, String receivedByName, Instant confirmedAt, UUID confirmedBy,
                       String confirmedByName, Instant closedAt, List<LineView> lines, List<String> warnings) {
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
     * Replace the whole count of a DRAFT (BR-02 tolerance, BR-03 lot and expiry, RECEIVING locations), and label,
     * recount or void each line's cartons to match it (SCRUM-473).
     */
    ReceiptView replaceLines(UUID receiptId, List<NewLine> lines);

    /**
     * The count is final: the goods are counted in as INBOUND stock at their receiving locations - into the line's
     * labelled cartons first, the rest loose - the order's progress is updated, and the receipt goes to QC or to
     * putaway.
     */
    ReceiptView confirm(UUID receiptId, UUID userId);

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

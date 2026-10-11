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

    /** One counted line: so many units of a PO line, from one lot, set down at a RECEIVING location. */
    record NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate,
                   String locationCode, String note) {
    }

    /** Part of a QC decision that leaves the QC area: where it goes and why. */
    record Part(int quantity, String locationCode, String reason) {
    }

    /** A QC decision on a whole line. {@code quarantined} and {@code rejected} may be null. */
    record Decision(int accepted, Part quarantined, Part rejected) {
    }

    /** Where a line is in the 3-step flow (docs 03 §5.2). */
    enum QcProgress { NOT_REQUIRED, AWAITING_MOVE_TO_QC, IN_QC_AREA, INSPECTED }

    record InspectionView(UUID id, QcOutcome outcome, int quantity, String locationCode, String reason,
                          UUID inspectedBy, Instant inspectedAt) {
    }

    record LineView(UUID id, UUID purchaseOrderLineId, Integer purchaseOrderLineNo, UUID inventoryItemId, String sku,
                    int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note,
                    boolean qcRequired, QcProgress qcProgress, String qcLocationCode, Instant movedToQcAt,
                    int quantityForPutaway, List<InspectionView> inspections) {
    }

    record ReceiptView(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber, UUID warehouseId,
                       GoodsReceiptStatus status, String deliveryNote, String note, Instant receivedAt,
                       UUID receivedBy, Instant confirmedAt, UUID confirmedBy, Instant closedAt,
                       List<LineView> lines) {
    }

    record Row(UUID id, String number, UUID purchaseOrderId, UUID warehouseId, GoodsReceiptStatus status,
               String deliveryNote, Instant receivedAt, UUID receivedBy, Instant confirmedAt, Instant closedAt) {
    }

    /** A DRAFT receipt for a CONFIRMED or PARTIALLY_RECEIVED order (BR-01), into the order's warehouse. */
    ReceiptView create(Create command, UUID userId);

    /** Replace the whole count of a DRAFT (BR-02 tolerance, BR-03 lot and expiry, RECEIVING locations). */
    ReceiptView replaceLines(UUID receiptId, List<NewLine> lines);

    /**
     * The count is final: the goods are counted in as INBOUND stock at their receiving locations, the
     * order's progress is updated, and the receipt goes to QC or to putaway.
     */
    ReceiptView confirm(UUID receiptId, UUID userId);

    ReceiptView cancel(UUID receiptId);

    /** Docs 03 step 7: a QC-required line's goods are now in a QUALITY_CONTROL area. */
    ReceiptView moveToQc(UUID receiptId, UUID lineId, String qcLocationCode, UUID userId);

    /** Docs 03 steps 8–9: accepted stays for putaway, quarantined and rejected go to QUARANTINE areas. */
    ReceiptView inspect(UUID receiptId, UUID lineId, Decision decision, UUID userId);

    ReceiptView get(UUID receiptId);

    PageResponse<Row> list(List<GoodsReceiptStatus> statuses, UUID purchaseOrderId, UUID warehouseId, String search,
                           Instant receivedFrom, Instant receivedTo, int page, int size, String sort);
}

package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Goods receipts against production orders (4A-5, kltn-docs 03 §4.1): {@link #createForProductionOrder}, then
 * {@link #replaceLines}, {@link #confirm} or {@link #cancel} - whole cartons of each production order line's standard
 * count, the production order's code as the lot, nothing over what is ordered. There is no quality check at the
 * warehouse (kltn-docs 03 BR-07): a confirmed receipt goes straight to putaway.
 *
 * <p>Purchasing is gone (4A-8, kltn-docs 00 of 04/11): the receipts counted against a purchase order, or none, before
 * 4A-5 ({@link GoodsReceiptType#PURCHASE}) are read as they stand and cancelled while they have counted nothing in;
 * nothing else moves them.</p>
 *
 * <p>Not on {@code procurement :: api}: nothing outside this module drives a receipt. Other modules
 * learn about receipts from the {@code GoodsReceiptConfirmed} and {@code ReceiptStockReadyForPutaway}
 * events.</p>
 */
public interface GoodsReceipts {

    /** 4A-5: goods delivered by a producer against one production order (kltn-docs 03 §4.1 step 1). */
    record CreateForProductionOrder(UUID productionOrderId, String deliveryNote, String note) {
    }

    /**
     * One counted line of a production receipt (4A-5): its {@code productionOrderLineId}, the RECEIVING area its cartons
     * stand in, and its cartons in {@code packages} - at least one, each holding the line's standard count (a carton's
     * quantity may be left out, 0; any other count is refused, BR-13). {@code quantity} may be left out too (0): it is
     * the cartons times their count. There is no lot to give: the lot is the production order's code (BR-03), and one
     * given is refused on its field; nor an expiry date, which nothing records (01 BR-17, 03 BR-06; team plan 5-1).
     * {@code unitOfMeasure}, when given, names the SKU's packing
     * unit holding that count; {@code sku}, when given, must be the production order line's.
     */
    record NewLine(UUID productionOrderLineId, int quantity, String lotNumber,
                   String locationCode, String note, String unitOfMeasure, List<NewPackage> packages, String sku) {

        public NewLine {
            packages = packages == null ? List.of() : List.copyOf(packages);
        }

        /** A line as a client usually sends it: its production order line, where its cartons are, the cartons. */
        public static NewLine ofProductionOrderLine(UUID productionOrderLineId, String locationCode,
                                                    List<NewPackage> packages) {
            return new NewLine(productionOrderLineId, 0, null, locationCode, null, null, packages, null);
        }
    }

    /**
     * One carton of a line: {@code lpn} names one labelled before to recount it; null labels a new one.
     * {@code quantity} 0 means the line's standard count (4A-5, BR-13).
     */
    record NewPackage(String lpn, int quantity) {
    }

    /**
     * {@code quantityForPutaway}: what the line puts away, every unit it received. {@code unitOfMeasure}: the packing
     * unit of the line's cartons, null for a historical line counted loose. {@code looseQuantity}: what was counted
     * into no carton, {@code quantity} less what the cartons were counted at ({@code receivedQty}) - none on a
     * production line. {@code packages}: the line's cartons, voided ones left out, by LPN - the client prints their
     * labels from these. {@code units}: every unit of the line's item, so the counting screen can offer the packing
     * units without the product permission the unit catalogue sits behind. {@code lotTracked}: always true - every
     * SKU is lot-tracked (01 BR-17, team plan 5-5) - kept for the receipt form.
     */
    record LineView(UUID id, UUID inventoryItemId, String sku, int quantity, String lotNumber,
                    String locationCode, String note, boolean lotTracked,
                    int quantityForPutaway, List<String> warnings, String unitOfMeasure, int looseQuantity,
                    List<PackageView> packages, List<UnitView> units, UUID productionOrderLineId,
                    ProductionLineProgress production) {
    }

    /**
     * 4A-5 (kltn-docs 03 §3 output, §4.1 step 3): a production receipt's line against its production order line, in
     * cartons and in units. {@code ordered}: what the line orders. {@code received}: what receipts confirmed so far
     * counted in - this one included once it is confirmed. {@code thisReceipt}: what this receipt counts.
     * {@code remaining}: what stays open once this receipt is counted in (for a draft: ordered less received less this
     * receipt; otherwise ordered less received). A short receipt leaves the rest open (BR-10).
     */
    record ProductionLineProgress(int unitsPerCarton, int orderedCartons, int receivedCartons, int thisReceiptCartons,
                                  int remainingCartons, int orderedUnits, int receivedUnits, int thisReceiptUnits,
                                  int remainingUnits) {
    }

    /**
     * A unit the line's item can be counted in, the base unit first, then by code. Cartons take an {@code active}
     * unit that is not {@code base}; {@code factor} is what one of them holds; {@code defaultPurchase} is the unit's
     * flag as the unit catalogue keeps it.
     */
    record UnitView(String unitOfMeasure, int factor, boolean base, boolean active, boolean defaultPurchase) {
    }

    /**
     * One carton of a line. {@code quantity}: what is in it now - what was counted into it while RECEIVING, what is
     * left in it once in stock. {@code receivedQty}: what was counted into it, which the receipt's
     * {@code looseQuantity} is reckoned from and which never changes once the carton is in stock. {@code nominalQty}:
     * what its unit holds. {@code status}: RECEIVING, IN_STOCK, CONSUMED or LOST. {@code locationCode}: where it
     * stands now, so a carton moved since it was received is found there.
     */
    record PackageView(UUID id, String lpn, int quantity, int receivedQty, int nominalQty, String status,
                       String locationCode) {
    }

    /**
     * The names next to the ids are for display (SCRUM-464); a name is null when its row is gone. {@code warnings} are
     * non-blocking business warnings; none is raised any more (the expiry warning went with expiry, team plan 5-1), so
     * the list is empty. {@code productionOrder*},
     * {@code producer*}: the production order of a PRODUCTION receipt (4A-5), null on a historical purchase receipt.
     */
    record ReceiptView(UUID id, String number, UUID warehouseId, String warehouseCode, String warehouseName,
                       GoodsReceiptStatus status, String deliveryNote, String note, Instant receivedAt,
                       UUID receivedBy, String receivedByName, Instant confirmedAt, UUID confirmedBy,
                       String confirmedByName, Instant closedAt, List<LineView> lines, List<String> warnings,
                       GoodsReceiptType receiptType, UUID productionOrderId, String productionOrderCode,
                       String productionOrderStatus, UUID producerId, String producerName) {
    }

    /** {@code receiptType}, {@code productionOrder*}, {@code producerName}: 4A-5; null on a historical purchase receipt. */
    record Row(UUID id, String number, UUID warehouseId, String warehouseCode, String warehouseName,
               GoodsReceiptStatus status, String deliveryNote, Instant receivedAt, UUID receivedBy,
               String receivedByName, Instant confirmedAt, UUID confirmedBy, String confirmedByName,
               Instant closedAt, GoodsReceiptType receiptType, UUID productionOrderId, String productionOrderCode,
               String producerName) {
    }

    /**
     * 4A-5 (kltn-docs 03 §4.1 step 1, BR-01, BR-15): a DRAFT receipt against one production order, at the warehouse it
     * is received at, while goods can be received against it; the production order registers the draft and is neither
     * cancelled nor closed while it is open. Its lines are its production order's lines, counted in whole cartons.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCTION_ORDER_NOT_FOUND};
     *         {@code PRODUCTION_ORDER_NOT_RECEIVABLE}; 403 for a production order received at a warehouse the caller is
     *         not assigned to
     */
    ReceiptView createForProductionOrder(CreateForProductionOrder command, UUID userId);

    /**
     * Replace the whole count of a DRAFT production receipt, and label, recount or void each line's cartons to match it
     * (SCRUM-473). A historical purchase receipt is refused ({@code INVALID_RECEIPT_TRANSITION}): it is only cancelled.
     */
    ReceiptView replaceLines(UUID receiptId, List<NewLine> lines);

    /**
     * The count is final: the cartons are counted in as INBOUND stock at their receiving locations, the production
     * order moves on, and the receipt goes to putaway. A historical purchase receipt is refused
     * ({@code INVALID_RECEIPT_TRANSITION}).
     */
    ReceiptView confirm(UUID receiptId, UUID userId);

    /**
     * A DRAFT, or a historical purchase receipt left waiting for the old over-tolerance approval: neither has counted
     * anything in (BR-05).
     */
    ReceiptView cancel(UUID receiptId);

    ReceiptView get(UUID receiptId);

    /** {@code productionOrderId}, {@code receiptType}: the receipts of one production order, of one type (4A-5). */
    PageResponse<Row> list(List<GoodsReceiptStatus> statuses, UUID warehouseId, String search, Instant receivedFrom,
                           Instant receivedTo, UUID productionOrderId, GoodsReceiptType receiptType, int page,
                           int size, String sort);
}

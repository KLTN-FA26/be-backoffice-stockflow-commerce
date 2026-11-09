package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;
import com.stockflow.procurement.internal.domain.ReceiptApproval;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Goods receipts against purchase orders (SCRUM-435, docs 03): count, confirm, then put away. There is no quality
 * check at the warehouse (kltn-docs 03 BR-07).
 *
 * <p>4A-5 (kltn-docs 03 §4.1): and against production orders - {@link #createForProductionOrder}, then the same
 * {@link #replaceLines}, {@link #confirm} and {@link #cancel}, which follow the receipt's type: whole cartons of each
 * production order line's standard count, the production order's code as the lot, nothing over what is ordered.</p>
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
     * SCRUM-478, BR-RCP-002: goods that arrived with no PO. {@code supplierId} and {@code reason} (free text) are
     * required; {@code warehouseId} is where they were received.
     */
    record CreateWithoutPo(UUID supplierId, UUID warehouseId, String reason, String deliveryNote, String note) {
    }

    /** 4A-5: goods delivered by a producer against one production order (kltn-docs 03 §4.1 step 1). */
    record CreateForProductionOrder(UUID productionOrderId, String deliveryNote, String note) {
    }

    /**
     * One counted line: so many units of a PO line, from one lot, set down at a RECEIVING location (SCRUM-473 design
     * §3). {@code quantity} is the line's total in base units. {@code packages} are the labelled cartons holding part
     * or all of it, the whole list each time; then {@code unitOfMeasure} is the active packing unit they are in. With
     * no cartons the line is loose and {@code unitOfMeasure} is absent or the item's base unit.
     *
     * <p>SCRUM-478: on a receipt without a PO there is no PO line; the line names the item by {@code sku}
     * ({@code purchaseOrderLineId} is then absent). On a receipt against a PO it is the reverse.</p>
     *
     * <p>4A-5: on a receipt against a production order the line names its {@code productionOrderLineId}, and lists
     * its cartons in {@code packages} - at least one, each holding the line's standard count (a carton's quantity may
     * be left out, 0; any other count is refused, BR-13). {@code quantity} may be left out too (0): it is the cartons
     * times their count. There is no lot, expiry date or PO line to give: the lot is the production order's code
     * (BR-03, BR-06). {@code unitOfMeasure}, when given, names the SKU's packing unit holding that count.</p>
     */
    record NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate,
                   String locationCode, String note, String unitOfMeasure, List<NewPackage> packages, String sku,
                   UUID productionOrderLineId) {

        public NewLine {
            packages = packages == null ? List.of() : List.copyOf(packages);
        }

        /** A line of a purchase receipt. */
        public NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate,
                       String locationCode, String note, String unitOfMeasure, List<NewPackage> packages, String sku) {
            this(purchaseOrderLineId, quantity, lotNumber, expiryDate, locationCode, note, unitOfMeasure, packages, sku,
                    null);
        }

        /** 4A-5: a line of a production receipt - its production order line, where its cartons are, the cartons. */
        public static NewLine ofProductionOrderLine(UUID productionOrderLineId, String locationCode,
                                                    List<NewPackage> packages) {
            return new NewLine(null, 0, null, null, locationCode, null, null, packages, null, productionOrderLineId);
        }

        /** A line of a receipt against a PO. */
        public NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate,
                       String locationCode, String note, String unitOfMeasure, List<NewPackage> packages) {
            this(purchaseOrderLineId, quantity, lotNumber, expiryDate, locationCode, note, unitOfMeasure, packages,
                    null);
        }

        /** A loose line, counted in the base unit. */
        public NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate,
                       String locationCode, String note) {
            this(purchaseOrderLineId, quantity, lotNumber, expiryDate, locationCode, note, null, List.of());
        }
    }

    /**
     * One carton of a line: {@code lpn} names one labelled before to recount it; null labels a new one. On a production
     * receipt {@code quantity} 0 means the line's standard count (4A-5, BR-13).
     */
    record NewPackage(String lpn, int quantity) {
    }

    /**
     * {@code quantityForPutaway}: what the line puts away, every unit it received. {@code unitOfMeasure}: the packing
     * unit of the line's cartons, null for a line counted loose.
     * {@code looseQuantity}: what was counted into no carton, {@code quantity} less what the cartons were counted at
     * ({@code receivedQty}); it stays as counted once the receipt is confirmed, whatever leaves the cartons later. {@code packages}: the
     * line's cartons, voided ones left out, by LPN - the client prints their labels from these. {@code units}: every
     * unit of the line's item, so the counting screen can offer the packing units without the product permission
     * the unit catalogue sits behind.
     */
    record LineView(UUID id, UUID purchaseOrderLineId, Integer purchaseOrderLineNo, UUID inventoryItemId, String sku,
                    int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note,
                    boolean lotTracked, boolean expiryTracked, int quantityForPutaway,
                    List<String> warnings, String unitOfMeasure,
                    int looseQuantity, List<PackageView> packages, List<UnitView> units,
                    String orderedUnitOfMeasure, UUID productionOrderLineId, ProductionLineProgress production) {
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
     * stands now, so a carton moved since it was received is found there.
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
     * null for a receipt that never needed the manager. {@code withoutPurchaseOrder} (SCRUM-478, BR-RCP-002): no PO
     * (so {@code purchaseOrderId} and {@code purchaseOrderNumber} are null; {@code supplierId} is the receipt's own and
     * {@code blindReason} says why); {@code referencedPurchaseOrder*} is a PO attached to it afterwards, a reference only.
     */
    record ReceiptView(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
                       String supplierName, UUID warehouseId, String warehouseCode, String warehouseName,
                       GoodsReceiptStatus status, String deliveryNote, String note, Instant receivedAt,
                       UUID receivedBy, String receivedByName, Instant confirmedAt, UUID confirmedBy,
                       String confirmedByName, Instant closedAt, List<LineView> lines, List<String> warnings,
                       Approval approval, boolean withoutPurchaseOrder, String blindReason,
                       UUID referencedPurchaseOrderId, String referencedPurchaseOrderNumber,
                       GoodsReceiptType receiptType, UUID productionOrderId, String productionOrderCode,
                       String productionOrderStatus, UUID producerId, String producerName) {
    }

    /** {@code receiptType}, {@code productionOrder*}, {@code producerName}: 4A-5; null on a purchase receipt. */
    record Row(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
               String supplierName, UUID warehouseId, String warehouseCode, String warehouseName,
               GoodsReceiptStatus status, String deliveryNote, Instant receivedAt, UUID receivedBy,
               String receivedByName, Instant confirmedAt, UUID confirmedBy, String confirmedByName,
               Instant closedAt, boolean withoutPurchaseOrder, GoodsReceiptType receiptType, UUID productionOrderId,
               String productionOrderCode, String producerName) {
    }

    /** A DRAFT receipt for a CONFIRMED or PARTIALLY_RECEIVED order (BR-01), into the order's warehouse. */
    ReceiptView create(Create command, UUID userId);

    /**
     * SCRUM-478, BR-RCP-002: a DRAFT receipt for goods that arrived with no PO, from this supplier, with a reason. Its
     * lines name items by SKU. {@link #confirm} always sends it to the warehouse manager: it is posted only once
     * {@link #approve approved}, and {@link #reject rejected} it is cancelled.
     */
    ReceiptView createWithoutPurchaseOrder(CreateWithoutPo command, UUID userId);

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
     * Owner decision NOPO-ATTACH ("gắn PO bổ sung"): the purchase order a receipt without one belongs to, as a
     * REFERENCE only - nothing is added to the order's received quantity, there is no tolerance and no effect on
     * three-way matching. The order must exist and be the receipt's supplier's ({@code RECEIPT_REFERENCE_SUPPLIER_MISMATCH});
     * the receipt must have no PO of its own and not be cancelled. Replaces an earlier reference.
     */
    ReceiptView referencePurchaseOrder(UUID receiptId, UUID purchaseOrderId);

    /**
     * Replace the whole count of a DRAFT (BR-03 lot and expiry, RECEIVING locations), and label, recount or void each
     * line's cartons to match it (SCRUM-473). A count beyond the tolerance (BR-02) is flagged, not refused (SCRUM-477).
     */
    ReceiptView replaceLines(UUID receiptId, List<NewLine> lines);

    /**
     * The count is final: the goods are counted in as INBOUND stock at their receiving locations - into the line's
     * labelled cartons first, the rest loose - the order's progress is updated, and the receipt goes to putaway.
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

    /**
     * BR-RCP-002: the manager refuses a receipt without a PO waiting in PENDING_APPROVAL; the whole receipt is
     * cancelled, the reason and the decider kept on it. Same refusals as {@link #approve}; a receipt against a PO is
     * {@code INVALID_RECEIPT_TRANSITION} (its excess is rejected, not the receipt).
     */
    ReceiptView reject(UUID receiptId, UUID approverId, String reason);

    /** A DRAFT, or a receipt waiting for approval: neither has counted anything in (BR-05). */
    ReceiptView cancel(UUID receiptId);

    ReceiptView get(UUID receiptId);

    /**
     * {@code withoutPurchaseOrder}: true keeps the receipts without a PO (SCRUM-478), false those against one.
     * {@code productionOrderId}, {@code receiptType}: the receipts of one production order, of one type (4A-5).
     */
    PageResponse<Row> list(List<GoodsReceiptStatus> statuses, UUID purchaseOrderId, UUID warehouseId, String search,
                           Instant receivedFrom, Instant receivedTo, Boolean withoutPurchaseOrder,
                           UUID productionOrderId, GoodsReceiptType receiptType, int page, int size, String sort);

    /** The list without the production order filters. */
    default PageResponse<Row> list(List<GoodsReceiptStatus> statuses, UUID purchaseOrderId, UUID warehouseId,
                                   String search, Instant receivedFrom, Instant receivedTo,
                                   Boolean withoutPurchaseOrder, int page, int size, String sort) {
        return list(statuses, purchaseOrderId, warehouseId, search, receivedFrom, receivedTo, withoutPurchaseOrder, null,
                null, page, size, sort);
    }
}

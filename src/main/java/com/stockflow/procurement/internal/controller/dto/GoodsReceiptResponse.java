package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A goods receipt with its lines. {@code quantityForPutaway} per line: what it puts away, every unit it received
 * (there is no quality check at the warehouse, kltn-docs 03 BR-07).
 *
 * <p>4A-5: {@code receiptType} is {@code PRODUCTION} - the receipt names its production order
 * ({@code productionOrderId}, {@code productionOrderCode} - also the lot of every line - its
 * {@code productionOrderStatus}, {@code producerId}, {@code producerName}), and each line names its
 * {@code productionOrderLineId} and carries {@code production}, the line against what it orders.</p>
 */
public record GoodsReceiptResponse(UUID id, String number, UUID warehouseId, String warehouseCode,
                                   String warehouseName, String status, String deliveryNote, String note,
                                   Instant receivedAt, UUID receivedBy, String receivedByName, Instant confirmedAt,
                                   UUID confirmedBy, String confirmedByName, Instant closedAt, List<Line> lines,
                                   String receiptType, UUID productionOrderId,
                                   String productionOrderCode, String productionOrderStatus, UUID producerId,
                                   String producerName) {

    /**
     * No expiry: there is none (03 BR-06, team plan 5-1). {@code unitOfMeasure}: the packing unit of
     * the line's cartons, null for a historical line counted loose. {@code looseQuantity}: the part counted into no
     * carton (the line less the cartons' {@code receivedQty}). {@code packages}: the line's cartons by LPN, voided ones
     * left out (SCRUM-473). {@code units}: every unit of the line's item, for the counting screen's unit picker.
     */
    public record Line(UUID id, UUID inventoryItemId, String sku, int quantity, String lotNumber,
                       String locationCode, String note,
                       int quantityForPutaway, String unitOfMeasure, int looseQuantity,
                       List<Package> packages, List<Unit> units,
                       @io.swagger.v3.oas.annotations.media.Schema(description = "4A-5: the production order line a"
                               + " production receipt's line counts")
                       UUID productionOrderLineId,
                       @io.swagger.v3.oas.annotations.media.Schema(description = "4A-5: the line against its"
                               + " production order line")
                       ProductionLineProgress production) {
    }

    /**
     * 4A-5 (kltn-docs 03 §3 output, §4.1 step 3): a production line in cartons of {@code unitsPerCarton} and in units.
     * {@code ordered*}: what the production order line orders. {@code received*}: what confirmed receipts counted in
     * so far, this one included once confirmed. {@code thisReceipt*}: this receipt's. {@code remaining*}: what stays
     * open once this receipt is counted in - for a draft, ordered less received less this receipt.
     */
    public record ProductionLineProgress(int unitsPerCarton, int orderedCartons, int receivedCartons,
                                         int thisReceiptCartons, int remainingCartons, int orderedUnits,
                                         int receivedUnits, int thisReceiptUnits, int remainingUnits) {
    }

    /**
     * A unit the line's item can be counted in, the base unit first, then by code. Cartons take an {@code active}
     * unit that is not {@code base}; {@code factor} is what one carton of it holds.
     */
    public record Unit(String unitOfMeasure, int factor, boolean base, boolean active) {
    }

    /**
     * One labelled carton. {@code quantity}: what is in it now - what was counted into it while RECEIVING, what is
     * left in it once in stock; {@code receivedQty}: what was counted into it, fixed once it is in stock;
     * {@code nominalQty}: what its unit holds; {@code status}: RECEIVING, IN_STOCK, CONSUMED or LOST;
     * {@code locationCode}: where it stands now - the receiving area, then wherever it was moved or put away.
     */
    public record Package(UUID id, String lpn, int quantity, int receivedQty, int nominalQty, String status,
                          String locationCode) {
    }
}

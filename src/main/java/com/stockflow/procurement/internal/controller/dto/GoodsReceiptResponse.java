package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A goods receipt with its lines. {@code qcProgress} per line: NOT_REQUIRED (2-step flow),
 * AWAITING_MOVE_TO_QC, IN_QC_AREA, INSPECTED. {@code quantityForPutaway}: what waits for putaway.
 *
 * <p>{@code warnings}: non-blocking business warnings (docs 03 BR-06), on the receipt and on the line they
 * come from. {@code EXPIRY_NEAR}: the lot expires within {@code stockflow.inventory.expiry-warning-days}
 * (30) of the receiving date; the manager decides whether to accept it. Empty when there is none.</p>
 */
public record GoodsReceiptResponse(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber,
                                   UUID supplierId, String supplierName, UUID warehouseId, String warehouseCode,
                                   String warehouseName, String status, String deliveryNote, String note,
                                   Instant receivedAt, UUID receivedBy, String receivedByName, Instant confirmedAt,
                                   UUID confirmedBy, String confirmedByName, Instant closedAt, List<Line> lines,
                                   List<String> warnings) {

    /**
     * {@code lotTracked} / {@code expiryTracked}: whether the line needs a lot / an expiry date (BR-03).
     * {@code unitOfMeasure}: the packing unit of the line's cartons, null for a loose line. {@code looseQuantity}:
     * the part no carton holds. {@code packages}: the line's cartons by LPN, voided ones left out (SCRUM-473).
     */
    public record Line(UUID id, UUID purchaseOrderLineId, Integer purchaseOrderLineNo, UUID inventoryItemId, String sku,
                       int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note,
                       boolean qcRequired, boolean lotTracked, boolean expiryTracked, String qcProgress,
                       String qcLocationCode, Instant movedToQcAt, int quantityForPutaway,
                       List<Inspection> inspections, List<String> warnings, String unitOfMeasure,
                       int looseQuantity, List<Package> packages) {
    }

    /**
     * One labelled carton. {@code quantity}: what was counted into it while RECEIVING, what is left in it once in
     * stock; {@code nominalQty}: what its unit holds; {@code status}: RECEIVING, IN_STOCK, CONSUMED or LOST.
     */
    public record Package(UUID id, String lpn, int quantity, int nominalQty, String status) {
    }

    public record Inspection(UUID id, String outcome, int quantity, String locationCode, String reason,
                             UUID inspectedBy, String inspectedByName, Instant inspectedAt) {
    }
}

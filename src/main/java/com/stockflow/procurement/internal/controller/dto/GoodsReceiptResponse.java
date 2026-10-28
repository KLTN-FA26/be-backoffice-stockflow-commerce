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
     * the part counted into no carton (the line less the cartons' {@code receivedQty}). {@code packages}: the line's cartons by LPN, voided ones left out (SCRUM-473).
     * {@code units}: every unit of the line's item, for the counting screen's unit picker.
     */
    public record Line(UUID id, UUID purchaseOrderLineId, Integer purchaseOrderLineNo, UUID inventoryItemId, String sku,
                       int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note,
                       boolean qcRequired, boolean lotTracked, boolean expiryTracked, String qcProgress,
                       String qcLocationCode, Instant movedToQcAt, int quantityForPutaway,
                       List<Inspection> inspections, List<String> warnings, String unitOfMeasure,
                       int looseQuantity, List<Package> packages, List<Unit> units,
                       @io.swagger.v3.oas.annotations.media.Schema(description = "SCRUM-473: the unit the PO line was"
                               + " ordered in, to pre-select; null for base units") String orderedUnitOfMeasure) {
    }

    /**
     * A unit the line's item can be counted in, the base unit first, then by code. Cartons take an {@code active}
     * unit that is not {@code base}; {@code factor} is what one carton of it holds; {@code defaultPurchase} marks
     * the unit the item is bought in.
     */
    public record Unit(String unitOfMeasure, int factor, boolean base, boolean active, boolean defaultPurchase) {
    }

    /**
     * One labelled carton. {@code quantity}: what is in it now - what was counted into it while RECEIVING, what is
     * left in it once in stock; {@code receivedQty}: what was counted into it, fixed once it is in stock;
     * {@code nominalQty}: what its unit holds; {@code status}: RECEIVING, IN_STOCK, CONSUMED or LOST;
     * {@code locationCode}: where it stands now - the receiving area, then the QC or quarantine area it went to.
     */
    public record Package(UUID id, String lpn, int quantity, int receivedQty, int nominalQty, String status,
                          String locationCode) {
    }

    public record Inspection(UUID id, String outcome, int quantity, String locationCode, String reason,
                             UUID inspectedBy, String inspectedByName, Instant inspectedAt) {
    }
}

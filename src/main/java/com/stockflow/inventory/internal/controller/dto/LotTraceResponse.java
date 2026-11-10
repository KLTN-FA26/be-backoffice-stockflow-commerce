package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One lot traced (SCRUM-153): where it came from, what is left of it and where, what holds it, and which customer
 * orders it left for. The ledger lines are paged separately ({@code /lots/{lotId}/movements}).
 */
@Schema(name = "LotTrace")
public record LotTraceResponse(Lot lot, List<Balance> balances, List<Hold> holds, List<OrderLeg> orders) {

    @Schema(name = "Lot", description = "A lot as received: one per goods receipt and (SKU, lot number); for goods "
            + "received against a production order, one per production order and SKU across its receipts, numbered "
            + "with the production order's code (kltn-docs 03 BR-03)")
    public record Lot(UUID lotId, String sku,
                      @Schema(description = "As printed; another receipt may use the same number for another lot")
                      String lotNumber,
                      Instant receivedAt,
                      @Schema(description = "GOODS_RECEIPT, PRODUCTION_ORDER, or UNTRACED for stock that entered "
                              + "without a receipt")
                      String origin,
                      UUID goodsReceiptId, String goodsReceiptNumber, UUID purchaseOrderId, String purchaseOrderNumber,
                      UUID supplierId,
                      @Schema(description = "As it read when the goods were received")
                      String supplierName,
                      UUID receivingWarehouseId,
                      long receivedQuantity,
                      @Schema(description = "On hand now, any status, in the warehouses asked about")
                      long onHand,
                      @Schema(description = "Free now: AVAILABLE and not held")
                      long available,
                      @Schema(description = "On hand now and held by allocations or other holds")
                      long held,
                      @Schema(description = "The production order a PRODUCTION_ORDER lot is of; null otherwise",
                              nullable = true)
                      UUID productionOrderId,
                      @Schema(description = "Its code as it read when the first goods arrived: also the lot number",
                              nullable = true)
                      String productionOrderCode) { }

    @Schema(name = "LotBalance")
    public record Balance(String locationCode, String status,
                          @Schema(description = "Null while hiddenDuringCount", nullable = true) Integer onHand,
                          @Schema(description = "Null while hiddenDuringCount", nullable = true) Integer held,
                          @Schema(description = "A cycle count holds a slot of the lot here and the caller lacks "
                                  + "inventory-cycle-counts:APPROVE: the quantities are withheld so the count stays blind")
                          boolean hiddenDuringCount) { }

    @Schema(name = "LotHold", description = "An allocation (allocatedFrom set) or another hold on the lot's stock")
    public record Hold(UUID reservationId, UUID orderId, String orderNumber,
                       UUID allocatedFrom, String locationCode, int quantity, String status, Instant reservedAt,
                       Instant closedAt) { }

    @Schema(name = "LotOrder", description = "A customer order the lot left for")
    public record OrderLeg(UUID orderId, String orderNumber, long picked, long putBack, Instant firstAt,
                           Instant lastAt) { }

    @Schema(name = "LotMovement", description = "One stock ledger line of the lot")
    public record Movement(UUID movementId, String type, String fromLocation, String toLocation, int quantity,
                           String fromStatus, String toStatus, String referenceType, UUID referenceId, UUID orderId,
                           String orderNumber, UUID actorId, Instant occurredAt, String reason) { }
}

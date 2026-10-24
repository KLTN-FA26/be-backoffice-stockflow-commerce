package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One lot traced (SCRUM-153): where it came from, what is left of it and where, what holds it, and which customer
 * orders it left for. The ledger lines are paged separately ({@code /lots/{lotId}/movements}).
 */
@Schema(name = "LotTrace")
public record LotTraceResponse(Lot lot, List<Balance> balances, List<Hold> holds, List<OrderLeg> orders) {

    @Schema(name = "Lot", description = "A lot as received: one per goods receipt and (SKU, lot number, expiry)")
    public record Lot(UUID lotId, String sku,
                      @Schema(description = "As printed; another receipt may use the same number for another lot")
                      String lotNumber,
                      LocalDate expiryDate, Instant receivedAt,
                      @Schema(description = "GOODS_RECEIPT, or UNTRACED for stock that entered without a receipt")
                      String origin,
                      UUID goodsReceiptId, String goodsReceiptNumber, UUID purchaseOrderId, String purchaseOrderNumber,
                      UUID supplierId,
                      @Schema(description = "As it read when the goods were received")
                      String supplierName,
                      UUID receivingWarehouseId,
                      long receivedQuantity,
                      @Schema(description = "On hand now, any status, in the warehouses asked about")
                      long onHand,
                      @Schema(description = "Free now: AVAILABLE, not past expiry, not held")
                      long available,
                      @Schema(description = "On hand now and held by allocations or production holds")
                      long held) { }

    @Schema(name = "LotBalance")
    public record Balance(String locationCode, String status, int onHand, int held) { }

    @Schema(name = "LotHold", description = "An allocation (allocatedFrom set) or another hold on the lot's stock")
    public record Hold(UUID reservationId, UUID orderId, String orderNumber, UUID productionOrderId,
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

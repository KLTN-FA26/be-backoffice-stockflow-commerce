package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One lot traced end to end (SCRUM-153): where it came from, what is left of it and where, what holds it, and which
 * customer orders it left for. A read model — the report assembles it, nothing changes it.
 */
public record LotTrace(Summary lot, List<Balance> balances, List<Hold> holds, List<OrderLeg> orders) {

    public LotTrace {
        balances = List.copyOf(balances);
        holds = List.copyOf(holds);
        orders = List.copyOf(orders);
    }

    /**
     * One lot and its current balance.
     *
     * @param origin           {@code GOODS_RECEIPT}, or {@code UNTRACED} for stock that entered without a receipt the
     *                         system recorded (an adjustment that found goods, stock older than lot tracking)
     * @param receivedQuantity units its receipt ledger lines brought in
     * @param onHand           units of it on hand now, in any status
     * @param available        units free to promise now: AVAILABLE, not past expiry, not held
     * @param held             units on hand now held by allocations or production holds
     */
    public record Summary(UUID lotId, String sku, String lotNumber, LocalDate expiryDate, Instant receivedAt,
                          String origin, UUID goodsReceiptId, String goodsReceiptNumber, UUID purchaseOrderId,
                          String purchaseOrderNumber, UUID supplierId, String supplierName, UUID receivingWarehouseId,
                          long receivedQuantity, long onHand, long available, long held) { }

    /** Units of the lot at one location in one status, now. */
    public record Balance(String locationCode, String status, int onHand, int held) { }

    /** An allocation or hold placed on the lot's stock, in any status. */
    public record Hold(UUID reservationId, UUID orderId, UUID productionOrderId, UUID allocatedFrom,
                       String locationCode, int quantity, String status, Instant reservedAt, Instant closedAt) { }

    /** A customer order the lot left for: units picked or packed out, units put back. */
    public record OrderLeg(UUID orderId, long picked, long putBack, Instant firstAt, Instant lastAt) { }

    /** One ledger line of the lot. */
    public record Movement(UUID movementId, String type, String fromLocation, String toLocation, int quantity,
                           String fromStatus, String toStatus, String referenceType, UUID referenceId, UUID orderId,
                           UUID actorId, Instant occurredAt, String reason) { }
}

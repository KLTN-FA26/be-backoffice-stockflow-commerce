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

    /**
     * Units of the lot at one location in one status, now. {@code onHand} and {@code held} are null, and
     * {@code hiddenDuringCount} true, where a cycle count holds a slot of the lot there and the reader may not see
     * the books (SCRUM-146).
     */
    public record Balance(String locationCode, String status, Integer onHand, Integer held,
                          boolean hiddenDuringCount) {
        public Balance(String locationCode, String status, int onHand, int held) {
            this(locationCode, status, onHand, held, false);
        }

        public Balance hidden() {
            return new Balance(locationCode, status, null, null, true);
        }
    }

    /** An allocation or hold placed on the lot's stock, in any status. */
    public record Hold(UUID reservationId, UUID orderId, String orderNumber, UUID productionOrderId,
                       UUID allocatedFrom, String locationCode, int quantity, String status, Instant reservedAt,
                       Instant closedAt) { }

    /**
     * A customer order the lot left for: units picked, or printed and consumed when their production order
     * completed (G4-Q2), and units put back.
     *
     * @param orderNumber the order's number as it read when the units moved (a snapshot on the ledger line)
     */
    public record OrderLeg(UUID orderId, String orderNumber, long picked, long putBack, Instant firstAt,
                           Instant lastAt) { }

    /** One ledger line of the lot. */
    public record Movement(UUID movementId, String type, String fromLocation, String toLocation, int quantity,
                           String fromStatus, String toStatus, String referenceType, UUID referenceId, UUID orderId,
                           String orderNumber, UUID actorId, Instant occurredAt, String reason) { }
}

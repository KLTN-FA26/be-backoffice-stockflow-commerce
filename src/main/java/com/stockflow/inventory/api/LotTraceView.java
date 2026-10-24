package com.stockflow.inventory.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One lot traced, as {@link LotTraceQueries} returns it: where it came from, what is left of it and where, what holds
 * it, and which customer orders it left for.
 *
 * <p>Numbers rather than ids for every document (receipt, purchase order, order): the readers of this record name
 * documents the way people do. Supplier and customer appear by name or number only; nothing personal.</p>
 *
 * @param origin           {@code GOODS_RECEIPT}, or {@code UNTRACED} for stock that entered without a recorded receipt
 * @param receivedQuantity units its receipt brought in
 * @param onHand           units of it on hand now, in any status
 * @param available        units free to promise now: AVAILABLE, not past expiry, not held
 * @param held             units on hand now held by allocations or production holds
 */
public record LotTraceView(String sku, String lotNumber, LocalDate expiryDate, Instant receivedAt, String origin,
                           String goodsReceiptNumber, String purchaseOrderNumber, String supplierName,
                           long receivedQuantity, long onHand, long available, long held,
                           List<Balance> balances, List<Hold> holds, List<OrderLeg> orders) {

    public LotTraceView {
        balances = List.copyOf(balances);
        holds = List.copyOf(holds);
        orders = List.copyOf(orders);
    }

    /** Units of the lot at one location in one status, now. */
    public record Balance(String locationCode, String status, int onHand, int held) {
    }

    /**
     * An allocation or hold placed on the lot's stock, in any status.
     *
     * @param orderNumber the customer order it was placed for; null for a production hold
     */
    public record Hold(String orderNumber, String locationCode, int quantity, String status, Instant reservedAt,
                       Instant closedAt) {
    }

    /** A customer order the lot left for: units picked (or printed and consumed), and units put back. */
    public record OrderLeg(String orderNumber, long picked, long putBack, Instant firstAt, Instant lastAt) {
    }
}

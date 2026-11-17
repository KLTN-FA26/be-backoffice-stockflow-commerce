package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.List;

/**
 * One lot traced, as {@link LotTraceQueries} returns it: where it came from, what is left of it and where, what holds
 * it, and which customer orders it left for.
 *
 * <p>Numbers rather than ids for every document (receipt, production order, order): the readers of this record
 * name documents the way people do. A customer appears by order number only; nothing personal. Since the rescope
 * goods come back printed against a production order (no purchase order, no supplier) and nothing expires.</p>
 *
 * @param origin              {@code GOODS_RECEIPT}, or {@code UNTRACED} for stock that entered without a recorded
 *                            receipt
 * @param productionOrderCode the production order the lot was printed for; null for a lot that is not one's
 * @param receivedQuantity    units its receipt brought in
 * @param onHand              units of it on hand now, in any status
 * @param available           units free to promise now: AVAILABLE and not allocated
 * @param held                units on hand now allocated to an order being picked
 */
public record LotTraceView(String sku, String lotNumber, Instant receivedAt, String origin,
                           String goodsReceiptNumber, String productionOrderCode,
                           long receivedQuantity, long onHand, long available, long held,
                           List<Balance> balances, List<Hold> holds, List<OrderLeg> orders) {

    public LotTraceView {
        balances = List.copyOf(balances);
        holds = List.copyOf(holds);
        orders = List.copyOf(orders);
    }

    /**
     * Units of the lot at one location in one status, now.
     *
     * @param onHand            null, with {@code held}, while a blind count covers that location: the lot screen hides
     *                          the books from whoever is counting (kltn-docs 20 BR-07), and so does this
     * @param hiddenDuringCount the figures are withheld for that reason
     */
    public record Balance(String locationCode, String status, Integer onHand, Integer held,
                          boolean hiddenDuringCount) {
    }

    /**
     * An allocation or hold placed on the lot's stock, in any status.
     *
     * @param orderNumber the customer order it was placed for
     */
    public record Hold(String orderNumber, String locationCode, int quantity, String status, Instant reservedAt,
                       Instant closedAt) {
    }

    /** A customer order the lot left for: units picked, and units put back. */
    public record OrderLeg(String orderNumber, long picked, long putBack, Instant firstAt, Instant lastAt) {
    }
}

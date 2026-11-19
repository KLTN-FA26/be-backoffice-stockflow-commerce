package com.stockflow.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order a fulfillment task may be opened for now (kltn-docs 07 step 1, 17 BR-02): released from its warehouse and
 * READY_TO_FULFILL. What the create-task dialog needs to choose one - who it is for, from where, and what is on it -
 * without the sales order screens' permission ({@code sales-orders:READ}), which warehouse roles do not hold.
 *
 * <p>Also an order still AWAITING_STOCK whose next delivery batch has cartons held at its shipping warehouse and no
 * task yet (07 §4.1 step 1): its part held may be picked before its release - to the staging area, or to dispatch when
 * Sales allowed a partial shipment (step 2, 17 §4.4 step 15).</p>
 *
 * @param customerName           the customer's registered name; null when the customer record is gone
 * @param warehouseId            the warehouse it was released from; for an order awaiting stock, its shipping warehouse
 * @param releasedAt             null for an order awaiting stock: it is not released
 * @param lines                  every line of the order, in line order
 * @param status                 READY_TO_FULFILL, PARTIALLY_FULFILLED (a later batch) or AWAITING_STOCK
 * @param partialShipmentAllowed Sales allowed the order to ship in parts (17 BR-15)
 */
public record FulfillmentCandidate(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String customerName,
        UUID warehouseId,
        Instant placedAt,
        Instant releasedAt,
        List<Line> lines,
        OrderStatus status,
        boolean partialShipmentAllowed
) {

    public FulfillmentCandidate {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A released order, READY_TO_FULFILL, that does not ship in parts. */
    public FulfillmentCandidate(UUID orderId, String orderNumber, UUID customerId, String customerName,
                                UUID warehouseId, Instant placedAt, Instant releasedAt, List<Line> lines) {
        this(orderId, orderNumber, customerId, customerName, warehouseId, placedAt, releasedAt, lines,
                OrderStatus.READY_TO_FULFILL, false);
    }

    /** The same candidate naming its customer. */
    public FulfillmentCandidate withCustomerName(String name) {
        return new FulfillmentCandidate(orderId, orderNumber, customerId, name, warehouseId, placedAt, releasedAt, lines,
                status, partialShipmentAllowed);
    }

    /**
     * One line. {@code printed}: a print line (it carries a confirmed design); its goods are made by production and
     * packed from the packing area, never picked (G4-Q2).
     */
    public record Line(UUID lineId, String sku, int quantity, boolean printed) {
    }
}

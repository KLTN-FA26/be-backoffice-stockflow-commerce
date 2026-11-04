package com.stockflow.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order a fulfillment task may be opened for now (kltn-docs 07 step 1, 17 BR-02): released from its warehouse and
 * READY_TO_FULFILL. What the create-task dialog needs to choose one - who it is for, from where, and what is on it -
 * without the sales order screens' permission ({@code sales-orders:READ}), which warehouse roles do not hold.
 *
 * @param customerName the customer's registered name; null when the customer record is gone
 * @param lines        every line of the order, in line order
 */
public record FulfillmentCandidate(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String customerName,
        UUID warehouseId,
        Instant placedAt,
        Instant releasedAt,
        List<Line> lines
) {

    public FulfillmentCandidate {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** The same candidate naming its customer. */
    public FulfillmentCandidate withCustomerName(String name) {
        return new FulfillmentCandidate(orderId, orderNumber, customerId, name, warehouseId, placedAt, releasedAt, lines);
    }

    /**
     * One line. {@code printed}: a print line (it carries a confirmed design); its goods are made by production and
     * packed from the packing area, never picked (G4-Q2).
     */
    public record Line(UUID lineId, String sku, int quantity, boolean printed) {
    }
}

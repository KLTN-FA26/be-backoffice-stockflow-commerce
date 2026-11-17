package com.stockflow.fulfillment.api;

import java.util.List;
import java.util.UUID;

/**
 * A shipment and the cartons it carried (team plan 4D-2).
 *
 * @param status  the shipment's status (kltn-docs 09 §5): {@code RETURNED} once the carrier brought it back (RTO)
 * @param cartons by LPN
 */
public record ShippedCartons(UUID shipmentId, String code, UUID orderId, UUID warehouseId, String status,
                             List<ShippedCarton> cartons) {

    public ShippedCartons {
        cartons = List.copyOf(cartons);
    }

    /** Whether the carrier brought it back to the warehouse (09 §5: DELIVERY_FAILED → RETURNED). */
    public boolean returned() {
        return "RETURNED".equals(status);
    }
}

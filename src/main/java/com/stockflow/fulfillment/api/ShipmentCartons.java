package com.stockflow.fulfillment.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The cartons a shipment carried, as a return receipt checks them (team plan 4D-2; kltn-docs 03 §4.2 step 10, BR-14;
 * 09 BR-06): only a carton of the shipment or order it is against is taken back. Read-only, and in no warehouse scope
 * of its own: the receipt asking checks the warehouse of what it is against before it shows anything.
 */
public interface ShipmentCartons {

    /** The shipment with the cartons it carried; empty when there is no such shipment. */
    Optional<ShippedCartons> ofShipment(UUID shipmentId);

    /**
     * The cartons delivered on the order: those its {@code DELIVERED} shipments carried, by LPN. A return request is
     * against a delivered order (kltn-docs 17 §4.10), so these are the cartons the customer may send back.
     */
    List<ShippedCarton> deliveredToOrder(UUID orderId);
}

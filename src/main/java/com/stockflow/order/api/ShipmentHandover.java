package com.stockflow.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fulfillment handed a shipment of the order to the carrier (kltn-docs 09 §4.1 step 3, §4.2 step 5): which units of
 * which order lines it carries. A split order is handed over in several of these (decision G4-Q3).
 *
 * @param shipmentId    fulfillment's shipment id; a second call with the same id and the same lines changes nothing
 * @param lines         the units in it, each line at most once, every quantity above zero
 * @param handedOverAt  when the manifest was signed
 * @param handedOverBy  who signed it for the warehouse
 * @param earlierPending the units of the order's other shipments that were made before this one and are not handed
 *                       over yet (G4-5, ready to dispatch): the money paid ahead goes to shipments in the order they
 *                       were made, so these keep their share of it and this one's COD is what its label said
 *                       ({@link OrderService#codFor}, 09 BR-04). Empty when there are none
 * @param carriage      what the customer may read of the shipment besides its status (G4-2a, 09 BR-05): its number,
 *                      parcels, carrier and delivery mode. Null from callers that do not know them
 * @param leavingWith   the units of the order's other shipments handed over in the same signature after this one
 *                      (PACK-PARTIAL): an order that does not allow partial shipments lets this one go only when, with
 *                      them, nothing of the order is left behind. Empty when there are none
 */
public record ShipmentHandover(UUID shipmentId, List<Line> lines, Instant handedOverAt, UUID handedOverBy,
                               List<Line> earlierPending, Carriage carriage, List<Line> leavingWith) {

    public ShipmentHandover {
        if (shipmentId == null || handedOverAt == null) {
            throw new IllegalArgumentException("A handover names its shipment and when it happened");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
        earlierPending = earlierPending == null ? List.of() : List.copyOf(earlierPending);
        leavingWith = leavingWith == null ? List.of() : List.copyOf(leavingWith);
    }

    /** Without the other shipments leaving in the same signature (callers written before PACK-PARTIAL). */
    public ShipmentHandover(UUID shipmentId, List<Line> lines, Instant handedOverAt, UUID handedOverBy,
                            List<Line> earlierPending, Carriage carriage) {
        this(shipmentId, lines, handedOverAt, handedOverBy, earlierPending, carriage, List.of());
    }

    /** Without the carriage (callers written before G4-2a). */
    public ShipmentHandover(UUID shipmentId, List<Line> lines, Instant handedOverAt, UUID handedOverBy,
                            List<Line> earlierPending) {
        this(shipmentId, lines, handedOverAt, handedOverBy, earlierPending, null);
    }

    /** Without shipments made before it and still waiting (callers written before G4-5). */
    public ShipmentHandover(UUID shipmentId, List<Line> lines, Instant handedOverAt, UUID handedOverBy) {
        this(shipmentId, lines, handedOverAt, handedOverBy, List.of(), null);
    }

    /**
     * The shipment as the customer portal shows it (G4-2a): fixed at the handover, never anything internal.
     *
     * @param shipmentCode  fulfillment's shipment number, {@code <order number>-S<n>}
     * @param packageCount  its parcels, at least one
     * @param carrierName   the carrier it was handed to
     * @param deliveryMode  PARCEL or FULL_TRUCKLOAD
     */
    public record Carriage(String shipmentCode, int packageCount, String carrierName, String deliveryMode) {
    }

    public record Line(UUID orderLineId, int quantity) {
    }
}

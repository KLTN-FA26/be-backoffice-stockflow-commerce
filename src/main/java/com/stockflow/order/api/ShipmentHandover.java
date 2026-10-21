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
 */
public record ShipmentHandover(UUID shipmentId, List<Line> lines, Instant handedOverAt, UUID handedOverBy) {

    public ShipmentHandover {
        if (shipmentId == null || handedOverAt == null) {
            throw new IllegalArgumentException("A handover names its shipment and when it happened");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public record Line(UUID orderLineId, int quantity) {
    }
}

package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WF4-COUNT-EDGE (a): what orders hold of a SKU at one warehouse by SKU (holds before their release, 20 §5.6), against
 * the whole free cartons there that could still cover them. A count's shortage may leave the holds uncovered; the order
 * module then gives up the difference.
 *
 * @param coverableUnits the units of whole cartons nothing holds, on pickable, usable locations of the warehouse - the
 *                       cartons a release there could allocate, counting those a pending count keeps out of allocation
 *                       for now (20 BR-11): a carton still in doubt is not yet a shortage
 * @param holds          the live SKU reservations of the SKU held at the warehouse, oldest first
 */
public record SkuHoldCover(String sku, String warehouseCode, int coverableUnits, List<Hold> holds) {

    public SkuHoldCover {
        holds = holds == null ? List.of() : List.copyOf(holds);
    }

    /** One live SKU reservation at the warehouse. */
    public record Hold(UUID reservationId, UUID orderId, int quantity, Instant reservedAt) {
    }

    /** Units held by SKU at the warehouse. */
    public int heldUnits() {
        return holds.stream().mapToInt(Hold::quantity).sum();
    }

    /** Units held that no carton covers any more; zero when every hold is covered. */
    public int uncoveredUnits() {
        return Math.max(0, heldUnits() - coverableUnits);
    }
}

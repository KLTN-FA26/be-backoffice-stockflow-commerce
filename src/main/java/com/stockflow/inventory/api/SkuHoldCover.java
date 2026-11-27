package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * WF4-COUNT-EDGE (a): what orders hold of a SKU at one warehouse by SKU (holds before their release, 20 §5.6), against
 * the whole free cartons there that could still cover them. A count's shortage may leave the holds uncovered; the order
 * module then gives up the difference.
 *
 * <p>A hold counts cartons of one count and is covered only by cartons of that count (ADR-0011). So the shortage is
 * per count ({@link #uncoveredCartonsByCount}), and whoever gives holds up gives up holds <b>of the short count</b>:
 * cutting a hold of another count frees cartons the short holds cannot take, and leaves them short.</p>
 *
 * @param coverableUnits the units of whole cartons nothing holds, on pickable, usable locations of the warehouse - the
 *                       cartons a release there could allocate, counting those a pending count keeps out of allocation
 *                       for now (20 BR-11): a carton still in doubt is not yet a shortage. When holds of some count
 *                       are short of cartons of that count, only the units that do cover a hold: free cartons of
 *                       another count cover nothing of it
 * @param freeCartons    the same free cartons, how many of each count ({@code nominal_qty} → cartons)
 * @param holds          the live SKU reservations of the SKU held at the warehouse, oldest first
 */
public record SkuHoldCover(String sku, String warehouseCode, int coverableUnits, Map<Integer, Integer> freeCartons,
                           List<Hold> holds) {

    public SkuHoldCover {
        freeCartons = freeCartons == null ? Map.of() : Map.copyOf(freeCartons);
        holds = holds == null ? List.of() : List.copyOf(holds);
    }

    /**
     * One live SKU reservation at the warehouse: {@code quantity} units, which are cartons of {@code unitsPerCarton}.
     */
    public record Hold(UUID reservationId, UUID orderId, int quantity, int unitsPerCarton, Instant reservedAt) {
    }

    /** Units held by SKU at the warehouse. */
    public int heldUnits() {
        return holds.stream().mapToInt(Hold::quantity).sum();
    }

    /**
     * Per count, the cartons held there that no free carton of that count covers any more; a count fully covered is
     * absent. Sorted by count, so a caller working through it does so in the same order every time.
     */
    public Map<Integer, Integer> uncoveredCartonsByCount() {
        Map<Integer, Integer> held = new TreeMap<>();
        holds.forEach(h -> held.merge(h.unitsPerCarton(), h.quantity() / h.unitsPerCarton(), Integer::sum));
        Map<Integer, Integer> uncovered = new TreeMap<>();
        held.forEach((count, cartons) -> {
            int missing = cartons - freeCartons.getOrDefault(count, 0);
            if (missing > 0) {
                uncovered.put(count, missing);
            }
        });
        return uncovered;
    }

    /** Units held that no carton covers any more, summed over the counts; zero when every hold is covered. */
    public int uncoveredUnits() {
        return uncoveredCartonsByCount().entrySet().stream().mapToInt(e -> e.getKey() * e.getValue()).sum();
    }
}

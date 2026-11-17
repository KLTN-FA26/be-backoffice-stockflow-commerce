package com.stockflow.warehouse.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the capacity board below its warehouse (kltn-docs 06 §4.6 step 4): a zone, the shelves of no zone, a
 * shelf, or a storage area. A bin counts in its shelf and in its shelf's zone (or {@link Kind#NO_ZONE}), so the zone
 * rows and the shelf rows are two views of the same bins; an area is its own row and has no capacity (06 BR-19,
 * SCRUM-666, decided 2026-11-13), only what stands in it.
 *
 * @param key               stable within one read: {@code ZONE:<id>}, {@code NO_ZONE}, {@code SHELF:<id>},
 *                          {@code AREA:<id>} - the keys {@link WarehouseCapacity#groupsOfLocation()} names
 * @param id                the zone, shelf or area; null for {@link Kind#NO_ZONE}
 * @param code              the shelf's or area's own code ({@code A01}, {@code RCV01}); null for a zone
 * @param name              its name; null for {@link Kind#NO_ZONE}, which the screen labels itself
 * @param binVolumeM3       Σ the computed volume of its usable bins, before the warehouse's utilization factor; null for
 *                          an area, which has no capacity
 * @param binsWithoutVolume its bins on the layout whose level has no clear height (C40): no volume to count
 */
public record CapacityGroup(String key, Kind kind, UUID id, String code, String name, BigDecimal binVolumeM3,
                            int binsWithoutVolume) {

    /** What a row groups. */
    public enum Kind {
        ZONE,
        /** The bins of the shelves of no zone (06 §4.6 step 4, "Không thuộc zone"). */
        NO_ZONE,
        SHELF,
        AREA
    }
}

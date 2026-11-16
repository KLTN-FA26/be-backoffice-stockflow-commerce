package com.stockflow.warehouse.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a warehouse offers to the capacity board (kltn-docs 06 §4.6): its bins with their computed volume and declared
 * load limit, its storage areas, and its utilisation factor. Only bins have a capacity: an area has none (06 BR-19,
 * decided 2026-11-13), so it is listed by type only, for the board to say which areas' cartons count as occupied.
 *
 * <p>A read-only snapshot in the caller's transaction; the arithmetic is the caller's.</p>
 *
 * @param utilizationFactor the share of the bins' volume cartons really fill, in (0, 1]
 */
public record CapacityLayout(UUID warehouseId, String prefix, BigDecimal utilizationFactor, List<Bin> bins,
                             List<Area> areas) {

    public CapacityLayout {
        bins = List.copyOf(bins);
        areas = List.copyOf(areas);
    }

    /**
     * A bin, whatever its status: cartons standing in a blocked bin still occupy the warehouse.
     *
     * @param zoneId      the zone of the bin's shelf; null for a shelf in no zone
     * @param zoneName    null with {@code zoneId}
     * @param volumeM3    width x length x the level's clear height, computed (06 BR-19); null on a legacy level declared
     *                    without a clear height
     * @param maxWeightKg the declared load limit putaway checks; null when none is declared (not checked)
     * @param usable      effective status {@code ACTIVE} - the bin's, its shelf's and the warehouse's: only these count
     *                    towards capacity
     */
    public record Bin(String locationCode, UUID zoneId, String zoneName, String shelfCode, BigDecimal volumeM3,
                      BigDecimal maxWeightKg, boolean usable) {
    }

    /** A storage area (a {@code NON_STORAGE} one has no location and is not listed). */
    public record Area(String locationCode, String code, String name, StorageLocationView.AreaType type) {
    }
}

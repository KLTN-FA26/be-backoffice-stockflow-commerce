package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A shelf with its levels and bins. Bins carry their storage location's code, class and status. */
public record ShelfResponse(UUID id, UUID warehouseId, UUID zoneId, String code, String name, String description,
                            Footprint footprint, boolean obstacle, PickFaces pickFaces,
                            StorageClass defaultStorageClass, LocationStatus status, long version,
                            List<Level> levels) {

    public record Footprint(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal length, int rotation) {
    }

    public record PickFaces(boolean north, boolean east, boolean south, boolean west) {
    }

    public record Level(UUID id, int levelIndex, BigDecimal elevation, BigDecimal usableHeight,
                        BigDecimal maxWeight, List<Bin> bins) {
    }

    /** @param storageClass the class in force - the override, or the shelf's default */
    public record Bin(UUID id, String code, String description, Footprint footprint, BinType type,
                      StorageClass storageClassOverride, UUID locationId, String locationCode,
                      StorageClass storageClass, Integer capacityUnits, BigDecimal maxWeight,
                      boolean pickable, boolean putawayTarget, LocationStatus status) {
    }
}

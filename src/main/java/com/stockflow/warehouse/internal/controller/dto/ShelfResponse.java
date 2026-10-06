package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A shelf with its levels and bins. Bins carry their storage location's code, class and status. */
public record ShelfResponse(UUID id, UUID warehouseId, UUID zoneId, String code, String name, String description,
                            FootprintResponse footprint, boolean obstacle, PickFaces pickFaces,
                            StorageClass defaultStorageClass, LocationStatus status, long version,
                            List<Level> levels) {

    public record PickFaces(boolean north, boolean east, boolean south, boolean west) {
    }

    public record Level(UUID id, int levelIndex, BigDecimal elevation, BigDecimal usableHeight,
                        BigDecimal maxWeight, List<Bin> bins) {
    }

    /** A bin just generated (BR-14), flat with its level: the response is one list across levels. */
    public record GeneratedBin(UUID id, UUID levelId, String code, UUID locationId, String locationCode,
                               FootprintResponse footprint) {
    }

    /** @param storageClass the class in force - the override, or the shelf's default */
    public record Bin(UUID id, String code, String description, FootprintResponse footprint, BinType type,
                      StorageClass storageClassOverride, UUID locationId, String locationCode,
                      StorageClass storageClass, Integer capacityUnits, BigDecimal maxWeight,
                      boolean pickable, boolean putawayTarget, LocationStatus status) {
    }
}

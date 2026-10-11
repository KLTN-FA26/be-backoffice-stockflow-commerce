package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.Bin;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.Shelf;
import com.stockflow.warehouse.internal.domain.ShelfLevel;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A shelf with its levels and bins, as the application layer hands it out. */
public record ShelfSummary(UUID id, UUID warehouseId, UUID zoneId, String code, String name, String description,
                           Footprint footprint, boolean obstacle, PickFaces pickFaces,
                           StorageClass defaultStorageClass, LocationStatus status, long version,
                           List<Level> levels) {

    public record Level(UUID id, int levelIndex, BigDecimal elevation, BigDecimal usableHeight,
                        BigDecimal maxWeight, List<BinEntry> bins) {

        static Level of(ShelfLevel level) {
            return new Level(level.id(), level.levelIndex(), level.measures().elevation(),
                    level.measures().usableHeight(), level.measures().maxWeight(),
                    level.bins().stream().map(BinEntry::of).toList());
        }
    }

    /**
     * A bin and its storage location, flattened: the location is what stock, scanners and other
     * modules see, so its code, class, capacity and status travel with the bin.
     *
     * @param storageClass the class in force - the override, or the shelf's default
     */
    public record BinEntry(UUID id, String code, String description, Footprint footprint, BinType type,
                           StorageClass storageClassOverride, UUID locationId, String locationCode,
                           StorageClass storageClass, Integer capacityUnits, BigDecimal maxWeight,
                           boolean pickable, boolean putawayTarget, LocationStatus status) {

        static BinEntry of(Bin bin) {
            StorageLocation location = bin.location();
            return new BinEntry(bin.id(), bin.code(), bin.details().description(), bin.footprint(),
                    bin.details().type(), bin.details().storageClassOverride(), location.id(),
                    location.locationCode(), location.storageClass(), location.capacityUnits(),
                    location.maxWeight(), location.pickable(), location.putawayTarget(), location.status());
        }
    }

    static ShelfSummary of(Shelf shelf) {
        return new ShelfSummary(shelf.id(), shelf.warehouseId(), shelf.zoneId(), shelf.code(), shelf.name(),
                shelf.description(), shelf.footprint(), shelf.obstacle(), shelf.pickFaces(),
                shelf.defaultStorageClass(), shelf.status(), shelf.version(),
                shelf.levels().stream().map(Level::of).toList());
    }
}

package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.Bin;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.Shelf;
import com.stockflow.warehouse.internal.domain.ShelfLevel;
import com.stockflow.warehouse.api.BinSize;
import com.stockflow.warehouse.internal.domain.StorageLocation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A shelf with its levels and bins, as the application layer hands it out. */
public record ShelfSummary(UUID id, UUID warehouseId, UUID zoneId, String code, String name, String description,
                           Footprint footprint, boolean obstacle, PickFaces pickFaces, LocationStatus status,
                           long version,
                           List<Level> levels) {

    public record Level(UUID id, int levelIndex, BigDecimal elevation, BigDecimal usableHeight,
                        BigDecimal maxWeight, List<BinEntry> bins) {

        static Level of(ShelfLevel level) {
            return new Level(level.id(), level.levelIndex(), level.measures().elevation(),
                    level.measures().usableHeight(), level.measures().maxWeight(),
                    level.bins().stream().map(bin -> BinEntry.of(bin, level.measures().usableHeight())).toList());
        }
    }

    /**
     * A bin and its storage location, flattened: the location is what stock, scanners and other
     * modules see, so its code, volume, load limit and status travel with the bin.
     *
     * @param capacityVolumeM3 width x length x the level's clear height ({@link BinSize}); null while the level
     *                         has none
     */
    public record BinEntry(UUID id, String code, String description, Footprint footprint, BinType type,
                           UUID locationId, String locationCode, BigDecimal capacityVolumeM3, BigDecimal maxWeight,
                           boolean pickable, boolean putawayTarget, LocationStatus status) {

        /** The bin {@code binId} of {@code level}, its volume from the level's clear height. */
        static BinEntry of(ShelfLevel level, UUID binId) {
            return of(level.bin(binId), level.measures().usableHeight());
        }

        static BinEntry of(Bin bin, BigDecimal usableHeight) {
            StorageLocation location = bin.location();
            return new BinEntry(bin.id(), bin.code(), bin.details().description(), bin.footprint(),
                    bin.details().type(), location.id(), location.locationCode(),
                    new BinSize(bin.footprint().width(), bin.footprint().length(), usableHeight).volumeM3(),
                    location.maxWeight(), location.pickable(), location.putawayTarget(), location.status());
        }
    }

    static ShelfSummary of(Shelf shelf) {
        return new ShelfSummary(shelf.id(), shelf.warehouseId(), shelf.zoneId(), shelf.code(), shelf.name(),
                shelf.description(), shelf.footprint(), shelf.obstacle(), shelf.pickFaces(), shelf.status(), shelf.version(),
                shelf.levels().stream().map(Level::of).toList());
    }
}

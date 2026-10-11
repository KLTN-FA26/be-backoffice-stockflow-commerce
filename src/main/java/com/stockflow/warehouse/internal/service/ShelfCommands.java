package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.BinDefaults;
import com.stockflow.warehouse.internal.domain.BinDetails;
import com.stockflow.warehouse.internal.domain.BinNamingScheme;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LevelMeasures;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.StorageClass;

import java.util.List;
import java.util.UUID;

/**
 * The write requests of {@link ShelfLayoutService}. Grouped in one file because they only make
 * sense together; each carries the domain's own value types ({@link Footprint}, {@link BinDetails},
 * ...), already validated when they were built.
 */
public final class ShelfCommands {

    private ShelfCommands() {
    }

    /** @param zoneId optional; must be a zone of the same warehouse */
    public record CreateShelf(UUID warehouseId, UUID zoneId, String code, String name, String description,
                              Footprint footprint, boolean obstacle, PickFaces pickFaces,
                              StorageClass defaultStorageClass) {
    }

    /** No code: it is part of every bin's location code. */
    public record UpdateShelf(UUID shelfId, UUID zoneId, String name, String description, Footprint footprint,
                              boolean obstacle, PickFaces pickFaces, StorageClass defaultStorageClass,
                              long expectedVersion) {
    }

    public record AddLevel(UUID shelfId, int levelIndex, LevelMeasures measures) {
    }

    /** No index: levels are never renumbered. */
    public record UpdateLevel(UUID shelfId, UUID levelId, LevelMeasures measures) {
    }

    public record AddBin(UUID shelfId, UUID levelId, String code, BinDetails details, LocationSettings settings) {
    }

    /** No code: it is part of the bin's location code. */
    public record UpdateBin(UUID shelfId, UUID levelId, UUID binId, BinDetails details, LocationSettings settings) {
    }

    /** BR-14. {@code rows} and {@code columns} only shape the grid; neither is stored. */
    public record GenerateBins(UUID shelfId, List<UUID> levelIds, int rows, int columns, BinNamingScheme scheme,
                               BinDefaults defaults) {
    }
}

package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole map of one warehouse, read as flat rows for drawing it. Kept off the domain ports: this
 * is a read model, and loading every Shelf aggregate to draw them would cost a query per shelf
 * (issue #25).
 *
 * <p>Every method filters by the warehouse id - levels and bins through their shelf - so reading a
 * map is the same seven queries for five shelves or five hundred, and no list of ids is ever sent
 * back to the database. Rows of every status are returned, {@code INACTIVE} included; the caller
 * decides what to show.</p>
 *
 * <p>The rows use wrapper types throughout - {@code Long version}, {@code Integer rotation}: Hibernate
 * picks the record's constructor by the selected values' types, which are always boxed, and a
 * primitive component makes it look for a constructor that does not exist.</p>
 */
public interface LayoutReadRepository {

    Optional<WarehouseRow> findWarehouse(UUID warehouseId);

    /** Ordered by name. */
    List<ZoneRow> findZones(UUID warehouseId);

    /** Ordered by code. */
    List<ShelfRow> findShelves(UUID warehouseId);

    /** Every level of every shelf of the warehouse, ordered by level index. */
    List<LevelRow> findLevels(UUID warehouseId);

    /** Every bin of the warehouse with its storage location, ordered by bin code. */
    List<BinRow> findBins(UUID warehouseId);

    /** Ordered by code; the location fields are {@code null} for a {@code NON_STORAGE} area. */
    List<AreaRow> findAreas(UUID warehouseId);

    /** In the order they were drawn (ids are time-ordered). */
    List<BoundaryRow> findBoundaries(UUID warehouseId);

    record WarehouseRow(UUID id, String prefix, String name, MapUnit mapUnit, BigDecimal mapWidth,
                        BigDecimal mapHeight, WarehouseStatus status, Long version) {
    }

    record ZoneRow(UUID id, String name, String color, Long version) {
    }

    record ShelfRow(UUID id, UUID zoneId, String code, String name, String description, BigDecimal x,
                    BigDecimal y, BigDecimal width, BigDecimal length, Integer rotation, Boolean obstacle,
                    Boolean pickNorth, Boolean pickEast, Boolean pickSouth, Boolean pickWest,
                    StorageClass defaultStorageClass, LocationStatus status, Long version) {
    }

    record LevelRow(UUID id, UUID shelfId, Integer levelIndex, BigDecimal elevation, BigDecimal usableHeight,
                    BigDecimal maxWeight) {
    }

    record BinRow(UUID id, UUID levelId, String code, String description, BigDecimal x, BigDecimal y,
                  BigDecimal width, BigDecimal length, Integer rotation, BinType type,
                  StorageClass storageClassOverride, UUID locationId, String locationCode,
                  StorageClass storageClass, Integer capacityUnits, BigDecimal maxWeight, Boolean pickable,
                  Boolean putawayTarget, LocationStatus status) {
    }

    record AreaRow(UUID id, String code, AreaType type, String name, BigDecimal x, BigDecimal y,
                   BigDecimal width, BigDecimal length, Integer rotation, Boolean obstacle, LocationStatus status,
                   Long version, UUID locationId, String locationCode, StorageClass storageClass,
                   Integer capacityUnits, BigDecimal maxWeight, Boolean pickable, Boolean putawayTarget) {
    }

    record BoundaryRow(UUID id, BoundaryType type, BigDecimal startX, BigDecimal startY, BigDecimal endX,
                       BigDecimal endY, Boolean passable, DoorStatus operationalStatus, Long version) {
    }
}

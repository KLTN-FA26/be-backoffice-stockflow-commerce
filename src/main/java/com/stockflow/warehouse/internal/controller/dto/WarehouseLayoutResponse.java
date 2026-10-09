package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One warehouse's whole map, for drawing it in one request.
 *
 * <p><b>Every place is included, {@code INACTIVE} ones too</b>; the client filters or greys them
 * out. Shelves, bins and areas carry their own {@code status} - what an admin set, and what an edit
 * form shows - and their {@code effectiveStatus}, narrowed by the warehouse and, for a bin, its shelf.
 * Colour the map by {@code effectiveStatus}; a bin set {@code ACTIVE} on a shelf in maintenance is
 * not usable. {@code usable} is {@code effectiveStatus == ACTIVE}.</p>
 *
 * <p>Null fields are left out of the JSON ({@code spring.jackson.default-property-inclusion:
 * non_null}): a {@code NON_STORAGE} area has no location fields and no {@code usable}, a wall no
 * {@code operationalStatus}.</p>
 */
public record WarehouseLayoutResponse(Warehouse warehouse, List<ZoneResponse> zones, List<Shelf> shelves,
                                      List<Area> areas, List<BoundaryResponse> boundaries) {

    public record Warehouse(UUID id, String prefix, String name, MapUnit mapUnit, BigDecimal mapWidth,
                            BigDecimal mapHeight, WarehouseStatus status, long version) {
    }

    public record Shelf(UUID id, UUID zoneId, String code, String name, String description,
                        FootprintResponse footprint, boolean obstacle, ShelfResponse.PickFaces pickFaces,
                        StorageClass defaultStorageClass, LocationStatus status, LocationStatus effectiveStatus,
                        long version, List<Level> levels) {
    }

    public record Level(UUID id, int levelIndex, BigDecimal elevation, BigDecimal usableHeight,
                        BigDecimal maxWeight, List<Bin> bins) {
    }

    /** @param storageClass the class in force - the override, or the shelf's default */
    public record Bin(UUID id, String code, String description, FootprintResponse footprint, BinType type,
                      StorageClass storageClassOverride, UUID locationId, String locationCode,
                      StorageClass storageClass, Integer capacityUnits, BigDecimal maxWeight, boolean pickable,
                      boolean putawayTarget, LocationStatus status, LocationStatus effectiveStatus, boolean usable) {
    }

    public record Area(UUID id, String code, AreaType type, String name, FootprintResponse footprint,
                       boolean obstacle, LocationStatus status, LocationStatus effectiveStatus, Boolean usable,
                       long version, UUID locationId, String locationCode, StorageClass storageClass,
                       Integer capacityUnits, BigDecimal maxWeight, Boolean pickable, Boolean putawayTarget) {
    }
}

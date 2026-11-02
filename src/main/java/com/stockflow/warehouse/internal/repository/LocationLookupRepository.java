package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * One storage location with the statuses its effective status is computed from (issue #18 D2), in
 * one query: the location, its warehouse, and for a bin its shelf.
 */
public interface LocationLookupRepository {

    /** Exact match; the caller upper-cases a scanned code first (every part is {@code [A-Z0-9]}). */
    Optional<LocationRow> findByLocationCode(String locationCode);

    Optional<LocationRow> findById(UUID locationId);

    java.util.List<LocationRow> findAreas(UUID warehouseId, com.stockflow.warehouse.internal.domain.AreaType type);

    /** The locations among {@code locationCodes} flagged {@code is_pickable}, whatever their status. */
    java.util.List<LocationRow> findPickable(java.util.Collection<String> locationCodes);

    /** The locations with these exact codes, whatever their status or flags; unknown codes are absent. */
    java.util.List<LocationRow> findByLocationCodes(java.util.Collection<String> locationCodes);

    /** The own codes of the zone's shelves in that warehouse, sorted; empty for an unknown or foreign zone. */
    java.util.List<String> findShelfCodesInZone(UUID warehouseId, UUID zoneId);

    /** The warehouse itself, for other modules naming it on a document (a purchase order's receiving warehouse). */
    Optional<WarehouseHeader> findWarehouseHeader(UUID warehouseId);

    /**
     * The bins of a warehouse a putaway may choose from: BIN locations of {@code storageClass} flagged {@code is_putaway_target} whose warehouse, shelf
     * and own status are all ACTIVE (the {@code EffectiveStatus.ofBin} rule, in the WHERE clause so the database does
     * the filtering), by location code. Capacity and weight as stored, null when unset. A non-null
     * {@code locationCode} narrows the same rule to that one location.
     */
    java.util.List<PutawayCandidateRow> findPutawayCandidates(UUID warehouseId, StorageClass storageClass,
                                                              String locationCode);

    record PutawayCandidateRow(UUID locationId, String locationCode, Integer capacityUnits,
                               java.math.BigDecimal maxWeight) {
    }

    record WarehouseHeader(UUID id, String prefix, String name, String address, WarehouseStatus status) {
    }

    /**
     * @param shelfStatus the bin's shelf; {@code null} for an area, whose own status is the
     *                    location's (D8)
     */
    record LocationRow(UUID id, StorageLocationKind kind, UUID warehouseId, String locationCode,
                       StorageClass storageClass, LocationStatus status, WarehouseStatus warehouseStatus,
                       LocationStatus shelfStatus, com.stockflow.warehouse.internal.domain.AreaType areaType) {
    }
}

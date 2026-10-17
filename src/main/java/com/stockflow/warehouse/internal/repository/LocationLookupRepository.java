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

    /** The warehouse itself, for other modules naming it on a document (a purchase order's receiving warehouse). */
    Optional<WarehouseHeader> findWarehouseHeader(UUID warehouseId);

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

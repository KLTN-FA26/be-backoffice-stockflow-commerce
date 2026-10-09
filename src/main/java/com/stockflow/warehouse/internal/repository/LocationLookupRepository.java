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

    /**
     * @param shelfStatus the bin's shelf; {@code null} for an area, whose own status is the
     *                    location's (D8)
     */
    record LocationRow(UUID id, StorageLocationKind kind, UUID warehouseId, String locationCode,
                       StorageClass storageClass, LocationStatus status, WarehouseStatus warehouseStatus,
                       LocationStatus shelfStatus) {
    }
}

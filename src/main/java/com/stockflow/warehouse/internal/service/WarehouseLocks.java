package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The warehouse row lock every write to a map takes first (issue #18 D4), in one place.
 *
 * <p>Rules that span aggregates - BR-06 inside the map, BR-07 no overlap, a zone name unique per
 * warehouse regardless of case - have no database twin, so two writers checking them at once
 * would both pass. Locking the warehouse row serialises every writer of one map; the second reads
 * what the first committed before deciding.</p>
 */
final class WarehouseLocks {

    private WarehouseLocks() {
    }

    /** Locks a warehouse named by the request. */
    static Warehouse lock(WarehouseRepository warehouses, UUID warehouseId) {
        return warehouses.findByIdForUpdate(warehouseId).orElseThrow(() -> warehouseNotFound(warehouseId));
    }

    /**
     * Locks the warehouse of a shelf, zone, area or boundary named by its own id - looked up without
     * reading the thing itself, which the caller reads only once the lock is held: read before, it
     * could be stale by the time the lock is granted. Safe because nothing on a map ever changes
     * warehouse.
     *
     * @param warehouseId the owner's warehouse id, empty when there is no such owner
     * @param notFound    the owner's own not-found error, for an unknown id
     */
    static Warehouse lockOwner(WarehouseRepository warehouses, Optional<UUID> warehouseId,
                               Supplier<BusinessException> notFound) {
        return warehouses.findByIdForUpdate(warehouseId.orElseThrow(notFound)).orElseThrow(notFound);
    }

    static BusinessException warehouseNotFound(UUID warehouseId) {
        return new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse " + warehouseId + " not found");
    }
}

package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.StorageLocation;
import com.stockflow.warehouse.internal.entity.StorageLocationJpaEntity;

/**
 * A storage location to its row and back - shared by the shelf and area mappers, since a location is
 * the same row whichever of the two owns it.
 */
final class StorageLocationPersistenceMapper {

    private StorageLocationPersistenceMapper() {
    }

    static StorageLocation toDomain(StorageLocationJpaEntity location) {
        return new StorageLocation(location.getId(), location.getWarehouseId(), location.getKind(),
                location.getLocationCode(), location.getStorageClass(),
                new LocationSettings(location.getCapacityUnits(), location.getMaxWeight(), location.isPickable(),
                        location.isPutawayTarget()),
                location.getStatus());
    }

    static StorageLocationJpaEntity toNewEntity(StorageLocation l) {
        return new StorageLocationJpaEntity(l.id(), l.warehouseId(), l.kind(), l.locationCode(), l.storageClass(),
                l.capacityUnits(), l.maxWeight(), l.pickable(), l.putawayTarget(), l.status());
    }

    /** Warehouse, kind and code are never written again (BR-13). */
    static void apply(StorageLocation l, StorageLocationJpaEntity row) {
        row.apply(l.storageClass(), l.capacityUnits(), l.maxWeight(), l.pickable(), l.putawayTarget(), l.status());
    }
}

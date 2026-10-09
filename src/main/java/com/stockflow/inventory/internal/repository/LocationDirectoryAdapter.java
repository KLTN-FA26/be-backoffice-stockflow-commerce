package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Repository;

/**
 * Asks {@code warehouse :: api} (issue #67): {@link StorageLocationView#usable()} already folds the
 * warehouse's and the shelf's status into the location's own, which is the part a direct read of
 * {@code warehouse.storage_location} got wrong — it saw a bin, not whether stock may go into it.
 */
@Repository
class LocationDirectoryAdapter implements LocationDirectory {

    private final WarehouseService warehouses;

    LocationDirectoryAdapter(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @Override
    public LocationState stateOf(LocationId location) {
        return warehouses.findLocation(location.code())
                .map(view -> view.usable() ? LocationState.USABLE : LocationState.UNUSABLE)
                .orElse(LocationState.UNKNOWN);
    }
}

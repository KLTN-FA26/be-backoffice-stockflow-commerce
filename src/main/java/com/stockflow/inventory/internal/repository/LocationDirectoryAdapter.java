package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Repository;

/**
 * Asks {@code warehouse :: api} (issue #67): {@code StorageLocationView.usable()} already folds the
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

    @Override
    public java.util.Map<String, java.util.UUID> pickable(java.util.Collection<String> locations) {
        return warehouses.pickableLocations(locations);
    }

    @Override
    public java.util.Optional<java.util.UUID> warehouseOf(LocationId location) {
        return warehouses.findLocation(location.code()).map(com.stockflow.warehouse.api.StorageLocationView::warehouseId);
    }

    @Override
    public boolean isPackingArea(LocationId location) {
        return warehouses.findLocation(location.code())
                .map(view -> view.areaType() == com.stockflow.warehouse.api.StorageLocationView.AreaType.PACKING)
                .orElse(false);
    }
}

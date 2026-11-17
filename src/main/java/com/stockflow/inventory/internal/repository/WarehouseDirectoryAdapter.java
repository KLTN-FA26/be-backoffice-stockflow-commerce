package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Asks {@code warehouse :: api} for the prefix, like {@link LocationDirectoryAdapter} asks for locations. */
@Repository
class WarehouseDirectoryAdapter implements WarehouseDirectory {

    private final WarehouseService warehouses;

    WarehouseDirectoryAdapter(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @Override
    public Optional<String> prefixOf(UUID warehouseId) {
        return warehouses.findWarehouse(warehouseId).map(WarehouseView::prefix);
    }
}

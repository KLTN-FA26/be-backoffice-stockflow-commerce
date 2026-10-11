package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.WarehouseDirectory;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** The warehouse's code — its {@code prefix} — from {@code warehouse :: api}. */
@Repository("orderWarehouseDirectory")
class WarehouseDirectoryAdapter implements WarehouseDirectory {

    private final WarehouseService warehouses;

    WarehouseDirectoryAdapter(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @Override
    public Optional<String> codeOf(UUID warehouseId) {
        return warehouses.findWarehouse(warehouseId).map(WarehouseView::prefix);
    }
}

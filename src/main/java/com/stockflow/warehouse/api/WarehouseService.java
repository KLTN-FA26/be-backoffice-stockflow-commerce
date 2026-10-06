package com.stockflow.warehouse.api;

/**
 * THE public API of the warehouse module - the only package other modules may import.
 *
 * <p>Deliberately still empty. Administering the map (warehouses, zones, shelves, areas) is used by
 * this module's own controllers only and lives on the internal
 * {@code internal.service.WarehouseLayoutService} (issue #18 D6). What other modules will call -
 * looking up a storage location by code or id, to validate the references procurement, inventory
 * and fulfillment hold to {@code warehouse.storage_location} - arrives here in #25.</p>
 *
 * <p>Every parameter and return type will be a record or enum declared in THIS package, never a
 * domain object or JPA entity ({@code ArchitectureTest.theApiPackageLeaksNothingInternal}).</p>
 */
public interface WarehouseService {

    // TODO(#25): Optional<StorageLocationView> findLocation(String locationCode) / (UUID locationId)
}

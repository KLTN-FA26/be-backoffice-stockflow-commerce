package com.stockflow.warehouse.internal.service;

import com.stockflow.common.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Administration of the warehouse map: warehouses and zones. Shelves, areas and boundaries have
 * interfaces of their own ({@link ShelfLayoutService}, {@link AreaLayoutService},
 * {@link BoundaryLayoutService}), one per controller.
 *
 * <p>Internal on purpose (issue #18 D6): only this module's controllers call it. What other modules
 * need from the warehouse - looking up a storage location - goes on {@code warehouse.api.WarehouseService}
 * (#25), so the public surface stays the few methods someone outside actually calls.</p>
 *
 * <p>Every change to the layout of a warehouse takes that warehouse's row lock first (issue #18 D4):
 * BR-06 and BR-07 are rules across several aggregates that no constraint can state, and the lock is
 * their second line of defence.</p>
 */
public interface WarehouseLayoutService {

    WarehouseSummary register(RegisterWarehouseCommand command);

    /** Shrinking the map is refused while anything on it would end up outside (BR-06). */
    WarehouseSummary update(UpdateWarehouseCommand command);

    WarehouseSummary activate(UUID warehouseId);

    /** Locations are not touched; they read as unusable while the warehouse is inactive (#18 D2). */
    WarehouseSummary deactivate(UUID warehouseId);

    PageResponse<WarehouseSummary> list(ListWarehousesQuery query);

    WarehouseSummary get(UUID warehouseId);

    ZoneSummary createZone(CreateZoneCommand command);

    ZoneSummary updateZone(UpdateZoneCommand command);

    /** Ordered by name. */
    List<ZoneSummary> listZones(UUID warehouseId);
}

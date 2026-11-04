package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@link WarehouseResources#LOCATIONS} once for the whole map. Its endpoints are spread over
 * several controllers - zones, shelves, areas, boundaries, the layout - and a resource code may be
 * declared only once, so it hangs on this class rather than on any one of them.
 */
@PermissionResource(code = WarehouseResources.LOCATIONS, group = "Warehouse", label = "Warehouse map",
        route = "/admin/warehouse-map", apiPath = "/api/v1/warehouses/{warehouseId}/layout",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
public final class WarehouseLocationResourceDeclaration {

    private WarehouseLocationResourceDeclaration() {
    }
}

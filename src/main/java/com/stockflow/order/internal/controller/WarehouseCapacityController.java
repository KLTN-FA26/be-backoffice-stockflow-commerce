package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.service.WarehouseCapacityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The warehouse capacity board (BE1; kltn-docs 06 §4.6, BR-20; C40-C43): per warehouse, capacity, occupied, incoming,
 * projected free, pending, free if all approved and outgoing, in m³ with percentages, and below it the zones, shelves
 * and areas. Read by the warehouse manager (their warehouses only), Sales, the Production Coordinator and the Sales
 * Manager (06 §2) - {@code warehouse-capacity:READ}, scope ALL, so a caller limited to warehouses is narrowed to theirs.
 *
 * <p>Under {@code /warehouse-capacity}, not {@code /warehouses/...}: those routes are the warehouse module's map.</p>
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/warehouse-capacity")
@Tag(name = "Warehouse capacity")
@PermissionResource(code = OrderResources.WAREHOUSE_CAPACITY, group = "Warehouse", label = "Warehouse capacity",
        route = "/warehouse/capacity", apiPath = "/api/v1/warehouse-capacity",
        actions = {Action.VIEW_PAGE, Action.READ})
class WarehouseCapacityController {

    private final WarehouseCapacityService capacity;

    WarehouseCapacityController(WarehouseCapacityService capacity) {
        this.capacity = capacity;
    }

    @GetMapping
    @Operation(summary = "The capacity board of every active warehouse the caller may see (a warehouse-bound caller: "
            + "theirs), by code, each with its zones, shelves and areas. Computed when read (06 BR-20)")
    @RequiresPermission(resource = OrderResources.WAREHOUSE_CAPACITY, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<WarehouseCapacityService.Board>> boards(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(capacity.boards());
    }

    @GetMapping("/{warehouseId}")
    @Operation(summary = "One warehouse's capacity board with its zones, shelves and areas (06 §4.6 step 4). "
            + "403 OUT_OF_DATA_SCOPE for a warehouse the caller is not assigned to; 404 WAREHOUSE_NOT_FOUND")
    @RequiresPermission(resource = OrderResources.WAREHOUSE_CAPACITY, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<WarehouseCapacityService.Board> board(@PathVariable UUID warehouseId,
                                                            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(capacity.board(warehouseId));
    }
}

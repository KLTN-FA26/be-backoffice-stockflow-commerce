package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The shipping warehouse of an order of a contract, and its stock held there (4B-7, kltn-docs 17 §4.2 steps 2-3,
 * BR-21, BR-22; 20 §5.6). Guarded by {@code sales-order-shipping-warehouses} with scope ALL - every customer's order,
 * from the back office; the portal customer is never granted it.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@PermissionResource(
        code = OrderResources.SHIPPING_WAREHOUSES,
        group = "Sales",
        label = "Order shipping warehouses",
        route = "/sales/orders",
        apiPath = "/api/v1/orders/{orderId}/shipping-warehouse",
        actions = {Action.READ, Action.UPDATE})
class ShippingWarehouseController {

    /** The warehouse Sales chooses. */
    @Schema(name = "ChooseShippingWarehouseRequest")
    record ChooseRequest(@NotNull(message = "warehouseId is required") UUID warehouseId) {
    }

    private final ShippingWarehouseService shippingWarehouses;

    ShippingWarehouseController(ShippingWarehouseService shippingWarehouses) {
        this.shippingWarehouses = shippingWarehouses;
    }

    @GetMapping("/{orderId}/shipping-warehouse/options")
    @Operation(summary = "The warehouses an order of a contract may ship from, best first: whole cartons of each line "
            + "available to promise at each (17 §4.2 step 2; the projected free space is not worked out yet - "
            + "capacityComputed false)")
    @RequiresPermission(resource = OrderResources.SHIPPING_WAREHOUSES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ShippingWarehouseService.Options> options(@PathVariable UUID orderId,
                                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(shippingWarehouses.options(orderId));
    }

    @PutMapping("/{orderId}/shipping-warehouse")
    @Operation(summary = "Choose the warehouse a confirmed order of a contract ships from (Sales): its stock is held "
            + "there by SKU, in whole cartons, as much as is available, with no deadline; the rest is topped up when "
            + "stock arrives. 409 ORDER_SHIPPING_WAREHOUSE_LOCKED for another warehouse once stock is held")
    @RequiresPermission(resource = OrderResources.SHIPPING_WAREHOUSES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ShippingWarehouseService.Coverage> choose(@PathVariable UUID orderId,
                                                                @Valid @RequestBody ChooseRequest request,
                                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(shippingWarehouses.choose(orderId, request.warehouseId(), user.userId()));
    }

    @GetMapping("/{orderId}/stock-coverage")
    @Operation(summary = "How much of an order of a contract is held at its shipping warehouse: per line and per "
            + "delivery batch, the earliest batch served first (17 §4.2 step 3)")
    @RequiresPermission(resource = OrderResources.SHIPPING_WAREHOUSES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ShippingWarehouseService.Coverage> coverage(@PathVariable UUID orderId,
                                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(shippingWarehouses.coverage(orderId));
    }
}

package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.controller.dto.DeliveryModeRequest;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.service.DeliveryModes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * LP-1 / LP-2 (lead's decisions 2026-10-22): the Order Coordinator chooses how an order travels - at release, or here
 * until packing starts. Guarded by {@code sales-order-delivery-modes:UPDATE} with scope ALL - every customer's order,
 * from the back office; the portal customer is never granted it. Reading the mode comes with the order
 * ({@code sales-orders:READ}).
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@PermissionResource(
        code = OrderResources.DELIVERY_MODES,
        group = "Sales",
        label = "Order delivery modes",
        route = "/sales/orders",
        apiPath = "/api/v1/orders/{orderId}/delivery-mode",
        actions = {Action.UPDATE})
class DeliveryModeController {

    private final DeliveryModes deliveryModes;

    DeliveryModeController(DeliveryModes deliveryModes) {
        this.deliveryModes = deliveryModes;
    }

    @PutMapping("/{orderId}/delivery-mode")
    @Operation(summary = "Change how an order travels (Order Coordinator): PARCEL or FULL_TRUCKLOAD. Recorded in the "
            + "order's history. 409 DELIVERY_MODE_LOCKED once packing started (its first package opened) or the order "
            + "is past packing, cancelled or closed; 409 DELIVERY_MODE_FTL_NO_COD for a full truckload whose balance "
            + "the carrier would collect on delivery (Sales changes the balance policy first)")
    @RequiresPermission(resource = OrderResources.DELIVERY_MODES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> change(@PathVariable UUID orderId,
                                             @Valid @RequestBody DeliveryModeRequest request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(deliveryModes.change(orderId, request.deliveryMode(),
                request.note(), user.userId())));
    }
}

package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.PartialShipmentRequest;
import com.stockflow.order.internal.service.PartialShipments;
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
 * PACK-PARTIAL (lead's decision 2026-10-21): Sales decides on the order whether it may ship in parts. Guarded by
 * {@code sales-order-partial-shipments:UPDATE} with scope ALL - every customer's order, from the back office; the
 * portal customer is never granted it.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@PermissionResource(
        code = OrderResources.PARTIAL_SHIPMENTS,
        group = "Sales",
        label = "Order partial shipments",
        route = "/sales/orders",
        apiPath = "/api/v1/orders/{orderId}/partial-shipment",
        actions = {Action.UPDATE})
class PartialShipmentController {

    private final PartialShipments partialShipments;

    PartialShipmentController(PartialShipments partialShipments) {
        this.partialShipments = partialShipments;
    }

    @PutMapping("/{orderId}/partial-shipment")
    @Operation(summary = "Allow or stop partial shipments of an order (Sales): packing a part of it, handing a part "
            + "over, shipping a mixed order's stock while its prints are made. Recorded in the order's history. "
            + "409 ORDER_PARTIAL_SHIPMENT_LOCKED once everything left or the order is cancelled / closed")
    @RequiresPermission(resource = OrderResources.PARTIAL_SHIPMENTS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> decide(@PathVariable UUID orderId,
                                             @Valid @RequestBody PartialShipmentRequest request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(partialShipments.decide(orderId, request.allowed(),
                request.note(), user.userId())));
    }
}

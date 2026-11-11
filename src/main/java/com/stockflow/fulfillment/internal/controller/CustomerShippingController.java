package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.service.OrderShippingTracking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The portal's delivery tracking of an order, vehicle by vehicle (3-6; kltn-docs 17 BR-27, §5.2). Served by fulfillment,
 * which holds the vehicles, rather than added to {@code GET /orders/me/{id}}: the order module may not depend on
 * fulfillment (fulfillment depends on it). The same permission as the customer's own order page,
 * {@code sales-orders:READ} with scope OWN; the customer is resolved from the sign-in, never from the request.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/orders/me")
@Tag(name = "Fulfillment - customer tracking", description = "The customer's view of their order's vehicles")
class CustomerShippingController {

    private final OrderShippingTracking tracking;

    CustomerShippingController(OrderShippingTracking tracking) {
        this.tracking = tracking;
    }

    @GetMapping("/{orderId}/vehicles")
    @Operation(summary = "The vehicles of one of the signed-in customer's own orders: carrier, waybill number, plate, "
            + "milestones and the shipments on each; someone else's order is 404")
    @RequiresPermission(resource = FulfillmentResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<List<OrderShippingTracking.Vehicle>> vehicles(@PathVariable UUID orderId,
                                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(tracking.vehicles(orderId, user));
    }
}

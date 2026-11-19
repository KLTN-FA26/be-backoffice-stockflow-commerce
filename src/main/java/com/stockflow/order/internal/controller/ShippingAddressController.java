package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.ShippingAddressChangeRequest;
import com.stockflow.order.internal.service.ShippingAddressChanges;
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
 * WF04 G2 (kltn-docs 17 §4.7 steps 23-24, BR-16; 08 BR-04, §6 edge case): Sales change an order's delivery address
 * until its first carton is labelled. Guarded by {@code sales-order-addresses:UPDATE} with scope ALL - every customer's
 * order, from the back office; the portal customer is never granted it.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@PermissionResource(
        code = OrderResources.ADDRESSES,
        group = "Sales",
        label = "Order delivery address",
        route = "/sales/orders",
        apiPath = "/api/v1/orders/{orderId}/shipping-address",
        actions = {Action.UPDATE})
class ShippingAddressController {

    private final ShippingAddressChanges addresses;

    ShippingAddressController(ShippingAddressChanges addresses) {
        this.addresses = addresses;
    }

    @PutMapping("/{orderId}/shipping-address")
    @Operation(summary = "Change the delivery address of an order (Sales) to one of its customer's shipping addresses, "
            + "until the order's first carton is labelled. Recorded in the order's history; the same address again "
            + "changes nothing. 404 ADDRESS_NOT_FOUND for an address not the customer's; 409 ORDER_ADDRESS_LOCKED once "
            + "a carton is labelled, or while the contract sent waits for its signature, or from PACKED on")
    @RequiresPermission(resource = OrderResources.ADDRESSES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> change(@PathVariable UUID orderId,
                                             @Valid @RequestBody ShippingAddressChangeRequest request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(addresses.change(orderId, request.shippingAddressId(),
                user.userId())));
    }
}

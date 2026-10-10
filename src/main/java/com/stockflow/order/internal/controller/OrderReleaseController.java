package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.ReleaseOrderRequest;
import com.stockflow.order.internal.service.OrderReleases;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Order Coordinator's release (SCRUM-423). Same resource as {@link OrderController}, which
 * declares it; {@code APPROVE} with scope ALL, like the admin cancellation: a customer is never
 * granted it, and releasing decides what the print shop spends a day on.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class OrderReleaseController {

    private final OrderReleases releases;

    OrderReleaseController(OrderReleases releases) {
        this.releases = releases;
    }

    @PostMapping("/{orderId}/release")
    @Operation(summary = "Release a paid order from a warehouse: to production when it has print lines, "
            + "otherwise ready to fulfil (BR-PRD-08 sample, BR-PRD-09 deposit)")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> release(@PathVariable UUID orderId,
                                              @Valid @RequestBody ReleaseOrderRequest request,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(releases.release(orderId, request.warehouseId(), user.userId())));
    }
}

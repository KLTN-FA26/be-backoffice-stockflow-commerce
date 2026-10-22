package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.controller.dto.DeliveryFailureRequest;
import com.stockflow.order.internal.controller.dto.HoldOrderRequest;
import com.stockflow.order.internal.controller.dto.ResolveHoldRequest;
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
 * The Order Coordinator's steps on an order (SCRUM-423 release; G3-2 hold, resolution, delivery outcome). Same resource
 * as {@link OrderController}, which declares it; {@code APPROVE} with scope ALL, like the admin cancellation: a customer
 * is never granted it, and {@code ScopeGuards.requireBackOffice} refuses one again.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class OrderReleaseController {

    private final OrderReleases releases;
    private final com.stockflow.order.api.OrderService orders;

    OrderReleaseController(OrderReleases releases, com.stockflow.order.api.OrderService orders) {
        this.releases = releases;
        this.orders = orders;
    }

    /**
     * The whole order delivered at once (SCRUM-431). G4-Q5: kept for an order with no shipment recorded on it; an
     * order with shipments answers 409 {@code ORDER_DELIVERY_VIA_SHIPMENT} - its outcome is recorded per shipment.
     */
    @PostMapping("/{orderId}/delivery")
    @Operation(summary = "Record that the carrier delivered the order (SHIPPED or DELIVERY_FAILED → DELIVERED); a "
            + "credit order's receivable opens, due after its days to pay. 409 ORDER_DELIVERY_VIA_SHIPMENT for an "
            + "order with shipments")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> delivered(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.recordDelivery(orderId, user.userId())));
    }

    /** G3-2: the carrier could not deliver the order (kltn-docs 09 §4.3 step 2); the same rule as above for shipments. */
    @PostMapping("/{orderId}/delivery-failure")
    @Operation(summary = "Record that the carrier could not deliver the order (SHIPPED → DELIVERY_FAILED), with the "
            + "reason; 409 ORDER_DELIVERY_VIA_SHIPMENT for an order with shipments")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> deliveryFailed(@PathVariable UUID orderId,
                                                     @Valid @RequestBody DeliveryFailureRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.recordDeliveryFailure(orderId, request.reasonCode(),
                request.note(), user.userId())));
    }

    /**
     * G3-2 / G3-Q9: the coordinator holds an order (kltn-docs 17 §5, 09 edge): from CONFIRMED up to PACKED, and a
     * partly shipped order. CREDIT and DESIGN_MISMATCH are the system's and refused here.
     */
    @PostMapping("/{orderId}/hold")
    @Operation(summary = "Put an order on hold with a reason (PAYMENT, ADDRESS, INVENTORY, CUSTOMER, FRAUD, OTHER)")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> hold(@PathVariable UUID orderId, @Valid @RequestBody HoldOrderRequest request,
                                           @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        if (!request.reasonCode().raisedByHand()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%s holds are placed by the system".formatted(request.reasonCode()),
                    java.util.List.of(new FieldError("reasonCode", "must be one of PAYMENT, ADDRESS, INVENTORY, "
                            + "CUSTOMER, FRAUD, OTHER", "NotRaisedByHand")));
        }
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.putOnHold(orderId, request.reasonCode(),
                request.note(), user.userId())));
    }

    /** G3-2: resolve the hold, back to where the order was held from. A credit hold is decided by credit approval. */
    @PostMapping("/{orderId}/hold/resolution")
    @Operation(summary = "Resolve an order's hold: back to the status it was held from; 409 ORDER_NOT_ON_HOLD, "
            + "409 ORDER_CREDIT_HOLD_USE_APPROVAL")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> resolveHold(@PathVariable UUID orderId,
                                                  @Valid @RequestBody ResolveHoldRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.resolveHold(orderId, request.note(), user.userId())));
    }

    @PostMapping("/{orderId}/release")
    @Operation(summary = "Release a paid order from a warehouse: to production when it has print lines, "
            + "otherwise ready to fulfil (BR-PRD-08 sample, BR-PRD-09 deposit). Optionally chooses how it travels "
            + "(deliveryMode, LP-1): a mode other than the order's needs sales-order-delivery-modes:UPDATE too (403); "
            + "409 DELIVERY_MODE_FTL_NO_COD for a full truckload whose balance is collected on delivery")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> release(@PathVariable UUID orderId,
                                              @Valid @RequestBody ReleaseOrderRequest request,
                                              @AuthenticatedUser CurrentUser user) {
        if (request.deliveryMode() != null && !user.can(OrderResources.DELIVERY_MODES, Action.UPDATE)) {
            // LP-1: releasing does not by itself let the caller choose the mode; keeping the order's own is fine.
            com.stockflow.order.api.DeliveryMode current = orders.findById(orderId)
                    .map(order -> order.lifecycle() == null ? com.stockflow.order.api.DeliveryMode.PARCEL
                            : order.lifecycle().deliveryMode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
            if (current != request.deliveryMode()) {
                user.require(OrderResources.DELIVERY_MODES, Action.UPDATE);
            }
        }
        return ApiResponse.ok(OrderWebMapper.toResponse(releases.release(orderId, request.warehouseId(),
                request.deliveryMode(), user.userId())));
    }
}

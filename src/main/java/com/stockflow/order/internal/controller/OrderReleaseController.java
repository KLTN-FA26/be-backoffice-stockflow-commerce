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
import com.stockflow.common.security.WarehouseScope;
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
 * The steps on an order that once were the order coordinator's (SCRUM-423 release; G3-2 hold, resolution, delivery
 * outcome). That role was retired (team plan 5-7, kltn-docs 00 §2) and its {@code sales-orders:APPROVE} split by who
 * now does each step (QD-12):
 * <ul>
 *   <li>release - {@code sales-order-releases:UPDATE}, the warehouse manager of the warehouse released to (17 BR-03):
 *       a caller limited to warehouses releases only to one of theirs;</li>
 *   <li>hold and its resolution - {@code sales-order-holds:UPDATE}, Sales (17 §5);</li>
 *   <li>the delivery outcome of an order without shipments - {@code fulfillment-delivery-outcomes:APPROVE}, as a
 *       shipment's (09 BR-02), the warehouse manager's (17 BR-27): only for an order released from one of the
 *       caller's warehouses.</li>
 * </ul>
 * Scope ALL, like the admin cancellation: a customer is never granted any of them, and
 * {@code ScopeGuards.requireBackOffice} refuses one again. An order row has no warehouse for the generic data-scope
 * filter (it has none until released), so the warehouse is checked here, against the request's or the order's.
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
    @RequiresPermission(resource = OrderResources.DELIVERY_OUTCOMES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> delivered(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        requireOrdersWarehouse(orderId);
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.recordDelivery(orderId, user.userId())));
    }

    /** G3-2: the carrier could not deliver the order (kltn-docs 09 §4.3 step 2); the same rule as above for shipments. */
    @PostMapping("/{orderId}/delivery-failure")
    @Operation(summary = "Record that the carrier could not deliver the order (SHIPPED → DELIVERY_FAILED), with the "
            + "reason; 409 ORDER_DELIVERY_VIA_SHIPMENT for an order with shipments")
    @RequiresPermission(resource = OrderResources.DELIVERY_OUTCOMES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> deliveryFailed(@PathVariable UUID orderId,
                                                     @Valid @RequestBody DeliveryFailureRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        requireOrdersWarehouse(orderId);
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.recordDeliveryFailure(orderId, request.reasonCode(),
                request.note(), user.userId())));
    }

    /**
     * G3-2 / G3-Q9: Sales holds an order (kltn-docs 17 §5, 09 edge): from CONFIRMED up to PACKED, and a partly shipped
     * order. CREDIT is the system's and refused here.
     */
    @PostMapping("/{orderId}/hold")
    @Operation(summary = "Put an order on hold with a reason (PAYMENT, ADDRESS, INVENTORY, CUSTOMER, FRAUD, OTHER)")
    @RequiresPermission(resource = OrderResources.HOLDS, action = Action.UPDATE, scope = DataScope.ALL)
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
    @RequiresPermission(resource = OrderResources.HOLDS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> resolveHold(@PathVariable UUID orderId,
                                                  @Valid @RequestBody ResolveHoldRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.resolveHold(orderId, request.note(), user.userId())));
    }

    /**
     * 17 BR-03: by the warehouse manager of the warehouse released to - one of the caller's, when they are limited.
     * 4B-8 (BR-29): an order with a signed delivery schedule releases its next delivery batch.
     */
    @PostMapping("/{orderId}/release")
    @Operation(summary = "Release an order - its next delivery batch, for an order with a delivery schedule - from its "
            + "shipping warehouse: the batch's holds are allocated on bins there. 409 DELIVERY_BATCH_NOT_NEXT, "
            + "409 ORDER_STOCK_NOT_HELD. 403 for a warehouse the caller is not assigned to")
    @RequiresPermission(resource = OrderResources.RELEASES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> release(@PathVariable UUID orderId,
                                              @Valid @RequestBody ReleaseOrderRequest request,
                                              @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        // Scope first: whether another warehouse exists is not an out-of-scope caller's to learn.
        WarehouseScope.requireWarehouse(request.warehouseId());
        return ApiResponse.ok(OrderWebMapper.toResponse(releases.release(orderId, request.warehouseId(),
                request.deliveryBatchId(), user.userId())));
    }

    /**
     * A caller limited to warehouses records the outcome only of an order released from one of them. An unknown order
     * passes, for the service to answer 404 as it does to anyone; an order released nowhere is refused to a limited
     * caller, since it reached no warehouse of theirs.
     */
    private void requireOrdersWarehouse(UUID orderId) {
        orders.findById(orderId).ifPresent(order -> WarehouseScope.requireWarehouse(order.warehouseId()));
    }
}

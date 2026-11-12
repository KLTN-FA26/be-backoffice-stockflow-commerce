package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.controller.dto.DeliveryFailureCaseRequests;
import com.stockflow.order.internal.controller.dto.DeliveryFailureCaseResponse;
import com.stockflow.order.internal.domain.DeliveryFailureCase;
import com.stockflow.order.internal.service.DeliveryFailureService;
import com.stockflow.order.internal.service.OrderWarehouseVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Delivery failure cases for Sales (WF-05 fix plan, sections 2-3; kltn-docs 09 §4.3, 17 §4.8): the shipments the
 * carrier brought back, once their goods are received, and the decision on each - deliver again, or cancel what was
 * not delivered.
 *
 * <p>No resource of its own. Reading is reading orders ({@code sales-orders:READ}, back office only - the portal
 * customer holds it for their own orders), limited for a warehouse manager as the order endpoints are
 * ({@link OrderWarehouseVisibility}), by one rule for both reads (WF-05 review N5): the cases whose goods came back to
 * their warehouses, in the list and on one order. An order with none of those is not found unless the order itself is
 * of their warehouses (then its list is empty), so the endpoint does not confirm another warehouse's order. Delivering again lifts the order's hold, so it answers to
 * {@code sales-order-holds:UPDATE}, Sales' (QD-12); cancelling what was not delivered is the closure of the rest with
 * its fee, so it answers to {@code sales-order-cancellations:UPDATE}, as {@code POST /orders/{id}/remainder-closure}
 * does - with a case, or, for goods never received back, without one. The release of a redelivery is the warehouse manager's, on the release endpoint as any release.</p>
 *
 * <p>Handler methods are public: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class DeliveryFailureController {

    private final DeliveryFailureService failures;
    private final OrderWarehouseVisibility warehouseVisibility;

    DeliveryFailureController(DeliveryFailureService failures, OrderWarehouseVisibility warehouseVisibility) {
        this.failures = failures;
        this.warehouseVisibility = warehouseVisibility;
    }

    @GetMapping("/delivery-failures")
    @Operation(summary = "Delivery failure cases, oldest first: shipments the carrier brought back whose goods were "
            + "received. status=OPEN is what waits for Sales to deliver again or cancel what was not delivered")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<DeliveryFailureCaseResponse>> list(
            @RequestParam(required = false) DeliveryFailureCase.Status status,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        // A warehouse manager reads the cases of goods that came back to their warehouses only (5-7 review M1).
        return ApiResponse.ok(failures.list(status, OrderWarehouseVisibility.limitOf(user), limit).stream()
                .map(DeliveryFailureController::toResponse).toList());
    }

    @GetMapping("/{orderId}/delivery-failures")
    @Operation(summary = "The delivery failure cases of one order, oldest first")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<DeliveryFailureCaseResponse>> ofOrder(@PathVariable UUID orderId,
                                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        // The list's rule (WF-05 review N5): a warehouse manager reads the cases whose goods came back to their
        // warehouses, whichever warehouse the order ships from.
        java.util.Set<UUID> limit = OrderWarehouseVisibility.limitOf(user);
        List<DeliveryFailureService.CaseView> cases = failures.ofOrder(orderId, limit);
        if (cases.isEmpty() && limit != null && !warehouseVisibility.visible(orderId, user)) {
            // As GET /orders/{id}: an order of another warehouse, with nothing of it back at theirs, is not found.
            throw new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId);
        }
        return ApiResponse.ok(cases.stream().map(DeliveryFailureController::toResponse).toList());
    }

    @PostMapping("/{orderId}/delivery-failures/{caseId}/redelivery")
    @Operation(summary = "Deliver again the goods of a shipment that came back (Sales): the order leaves its hold, the "
            + "goods are held for it as they are put away and the warehouse manager releases them. A trip the "
            + "customer made fail (REFUSED, WRONG_ADDRESS) is charged with failedTripFee on a fee item; any other "
            + "reason charges nothing. 409 DELIVERY_FAILURE_INVALID_TRANSITION when the case is not OPEN")
    @RequiresPermission(resource = OrderResources.HOLDS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<DeliveryFailureCaseResponse> redeliver(@PathVariable UUID orderId, @PathVariable UUID caseId,
            @Valid @RequestBody DeliveryFailureCaseRequests.Redelivery request, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(failures.redeliver(orderId, caseId,
                new DeliveryFailureService.Redeliver(request.note(), request.failedTripFee()), user.userId())));
    }

    @PostMapping("/{orderId}/delivery-failures/{caseId}/cancellation")
    @Operation(summary = "Cancel what a failed delivery left undelivered (Sales): the rest of the order is closed "
            + "with the fee agreed for the goods already made - they are not refunded by default - and what was "
            + "collected above the goods delivered and the fee becomes refundable. Every undecided case of the order "
            + "is cancelled with it, and a redelivery released to the warehouse is withdrawn while nothing of it is "
            + "picked. 409 DELIVERY_FAILURE_INVALID_TRANSITION when the case is closed or goods released to be "
            + "delivered again are picked already; 409 DELIVERY_FAILURE_REST_NOT_CLOSABLE while the order still owes "
            + "units and is on hold for another reason or packed")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<DeliveryFailureCaseResponse> cancel(@PathVariable UUID orderId, @PathVariable UUID caseId,
            @Valid @RequestBody DeliveryFailureCaseRequests.Cancellation request, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(failures.cancelUndelivered(orderId, caseId,
                new DeliveryFailureService.Cancel(request.note(), OrderWebMapper.toFee(request.agreedFee())),
                user.userId())));
    }

    @PostMapping("/{orderId}/delivery-failures/cancellation")
    @Operation(summary = "Cancel what was not delivered while the order is held for a shipment that came back and has "
            + "no case yet - its goods lost on the way back, or their return receipt not made (Sales). The same "
            + "closure and money as the case's cancellation; the note is mandatory. Goods received back later open a "
            + "case that is cancelled at once. Answers the order's cases. 409 DELIVERY_FAILURE_INVALID_TRANSITION when "
            + "the order is not held for a failed delivery or every shipment that came back has its case")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<List<DeliveryFailureCaseResponse>> cancelWithoutCase(@PathVariable UUID orderId,
            @Valid @RequestBody DeliveryFailureCaseRequests.Cancellation request, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(failures.cancelWithoutCase(orderId,
                new DeliveryFailureService.Cancel(request.note(), OrderWebMapper.toFee(request.agreedFee())),
                user.userId()).stream().map(DeliveryFailureController::toResponse).toList());
    }

    private static DeliveryFailureCaseResponse toResponse(DeliveryFailureService.CaseView view) {
        DeliveryFailureCase c = view.failure();
        return new DeliveryFailureCaseResponse(c.id(), c.orderId(), view.orderNumber(), view.customerId(),
                view.orderStatus().name(), c.shipmentId(), c.deliveryBatchId(), c.receiptId(), c.receiptNumber(),
                c.warehouseId(), c.failureReason().name(), c.failureNote(), c.customerPaysFailedTrip(),
                c.status().name(), c.openedAt(), c.decidedBy(), c.decidedAt(), c.decisionNote(), c.failedTripFee(),
                c.feeInstallmentId(), c.carrierClaim(), view.readyForRelease(), c.releasedAt(), c.releasedBy(),
                c.redeliveryShipmentId(), c.redeliveredAt(), c.closedAt(),
                c.units().entrySet().stream().map(line -> new DeliveryFailureCaseResponse.Line(line.getKey(),
                        line.getValue(), view.heldUnits().getOrDefault(line.getKey(), 0))).toList(),
                c.cartons().stream().map(carton -> new DeliveryFailureCaseResponse.Carton(carton.lpn(), carton.sku(),
                        carton.lotNumber(), carton.quantity(), carton.condition())).toList(),
                c.version());
    }
}

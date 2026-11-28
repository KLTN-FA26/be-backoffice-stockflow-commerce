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
 * carrier brought back, once their goods are received. Since RTM-RETURNED and WF-05 Q-7 a case is a record of what came
 * back, closed by the system when the receipt settled the shipment ({@code closure} RECORDED_ON_RETURN); only an order
 * from before RTM-RETURNED still owing the shipment's units waits for Sales to cancel what was not delivered.
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
    private final com.stockflow.identity.api.IdentityService identities;
    private final com.stockflow.customer.api.CustomerService customers;
    private final com.stockflow.warehouse.api.WarehouseService warehouses;
    /** 17 BR-32: Sales' decisions on a case, recorded as done on the owner's behalf when someone else makes them. */
    private final com.stockflow.order.internal.service.OrderOperations operations;

    DeliveryFailureController(DeliveryFailureService failures, OrderWarehouseVisibility warehouseVisibility,
                              com.stockflow.identity.api.IdentityService identities,
                              com.stockflow.customer.api.CustomerService customers,
                              com.stockflow.warehouse.api.WarehouseService warehouses,
                              com.stockflow.order.internal.service.OrderOperations operations) {
        this.operations = operations;
        this.failures = failures;
        this.warehouseVisibility = warehouseVisibility;
        this.identities = identities;
        this.customers = customers;
        this.warehouses = warehouses;
    }

    @GetMapping("/delivery-failures")
    @Operation(summary = "Delivery failure cases, newest first (at most 200): shipments the carrier brought back "
            + "whose goods were received. status=OPEN is what waits for Sales to cancel what was not delivered (orders from before "
            + "RTM-RETURNED only)")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<DeliveryFailureCaseResponse>> list(
            @RequestParam(required = false) DeliveryFailureCase.Status status,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        // A warehouse manager reads the cases of goods that came back to their warehouses only (5-7 review M1).
        return ApiResponse.ok(toResponses(failures.list(status, OrderWarehouseVisibility.limitOf(user), limit)));
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
        return ApiResponse.ok(toResponses(cases));
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
        return ApiResponse.ok(toResponses(List.of(operations.cancelUndelivered(orderId, caseId,
                new DeliveryFailureService.Cancel(request.note(),
                        OrderWebMapper.toFee(request.agreedFee(), request.feeBearer(), false)),
                user.userId()))).getFirst());
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
        return ApiResponse.ok(toResponses(operations.cancelUndeliveredWithoutCase(orderId,
                new DeliveryFailureService.Cancel(request.note(),
                        OrderWebMapper.toFee(request.agreedFee(), request.feeBearer(), false)),
                user.userId())));
    }

    /**
     * The cases with the names beside their ids: one lookup each for the people, the customers and the warehouses of
     * the whole answer - not one per case.
     */
    private List<DeliveryFailureCaseResponse> toResponses(List<DeliveryFailureService.CaseView> views) {
        java.util.Map<UUID, String> people = identities.displayNames(views.stream()
                .flatMap(v -> java.util.stream.Stream.of(v.failure().decidedBy(), v.failure().releasedBy()))
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        java.util.Map<UUID, com.stockflow.customer.api.CustomerLabel> labels = customers.labels(views.stream()
                .map(DeliveryFailureService.CaseView::customerId).filter(java.util.Objects::nonNull).distinct()
                .toList());
        java.util.Map<UUID, com.stockflow.warehouse.api.WarehouseView> sites = warehouses.findWarehouses(views.stream()
                .map(v -> v.failure().warehouseId()).filter(java.util.Objects::nonNull).distinct().toList());
        return views.stream().map(view -> toResponse(view, people, labels, sites)).toList();
    }

    private static DeliveryFailureCaseResponse toResponse(DeliveryFailureService.CaseView view,
            java.util.Map<UUID, String> people,
            java.util.Map<UUID, com.stockflow.customer.api.CustomerLabel> labels,
            java.util.Map<UUID, com.stockflow.warehouse.api.WarehouseView> sites) {
        DeliveryFailureCase c = view.failure();
        // WF-05 F-4 (BE-42): a case the system closed carries its English note, which the back office showed as it is;
        // the machine-readable closure says it, and the note is not passed on.
        String closure = DeliveryFailureCaseResponse.closureOf(c.status().name(), c.decisionNote(),
                DeliveryFailureService.systemClosureNote());
        String decisionNote = "RECORDED_ON_RETURN".equals(closure) ? null : c.decisionNote();
        com.stockflow.customer.api.CustomerLabel label = view.customerId() == null ? null
                : labels.get(view.customerId());
        com.stockflow.warehouse.api.WarehouseView site = c.warehouseId() == null ? null : sites.get(c.warehouseId());
        return new DeliveryFailureCaseResponse(c.id(), c.orderId(), view.orderNumber(), view.customerId(),
                view.orderStatus().name(), c.shipmentId(), c.deliveryBatchId(), c.receiptId(), c.receiptNumber(),
                c.warehouseId(), c.failureReason().name(), c.failureNote(), c.customerPaysFailedTrip(),
                c.status().name(), closure, c.openedAt(), c.decidedBy(), c.decidedAt(), decisionNote, c.failedTripFee(),
                c.feeInstallmentId(), c.carrierClaim(), view.readyForRelease(), c.releasedAt(), c.releasedBy(),
                c.redeliveryShipmentId(), c.redeliveredAt(), c.closedAt(),
                c.units().entrySet().stream().map(line -> new DeliveryFailureCaseResponse.Line(line.getKey(),
                        line.getValue(), view.heldUnits().getOrDefault(line.getKey(), 0))).toList(),
                c.cartons().stream().map(carton -> new DeliveryFailureCaseResponse.Carton(carton.lpn(), carton.sku(),
                        carton.lotNumber(), carton.quantity(), carton.condition())).toList(),
                c.version(), c.decidedBy() == null ? null : people.get(c.decidedBy()),
                c.releasedBy() == null ? null : people.get(c.releasedBy()),
                label == null ? null : label.name(), label == null ? null : label.code(),
                site == null ? null : site.prefix(), site == null ? null : site.name());
    }
}

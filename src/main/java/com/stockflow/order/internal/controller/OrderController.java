package com.stockflow.order.internal.controller;

import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.controller.dto.OrderStatusChangeResponse;
import com.stockflow.order.internal.controller.dto.CloseRemainderRequest;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.internal.controller.dto.AdminCancelOrderRequest;
import com.stockflow.order.internal.controller.dto.CancelOrderRequest;
import com.stockflow.order.internal.controller.dto.CancellationResultResponse;
import com.stockflow.order.internal.controller.dto.CustomerOrderCardResponse;
import com.stockflow.order.internal.controller.dto.CustomerOrderDetailResponse;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.TransferInstructionsResponse;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.service.CustomerOrders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * HTTP entry point for orders. Thin: request in, command out, response back.
 *
 * <p><b>No order is placed here.</b> The customer does not order on the portal, and "order again" opens a chat with
 * Sales (kltn-docs 14 BR-01, BR-02; 13 BR-08): an order is drawn up by Sales from an accepted quote
 * ({@code POST /api/v1/quotes/{id}/conversion}). Checkout and the reorder of a quoted order went with team plan 5-2.</p>
 *
 * <p><b>Handler methods are public.</b> {@code @RequiresPermission} and {@code @Auditable} are
 * applied by Spring AOP proxies, which advise a package-private method only when the generated
 * proxy happens to land in the same package and classloader. When that does not hold the advice is
 * skipped silently - and a skipped {@code @RequiresPermission} is an endpoint with no authorisation
 * at all. Public is the only form where the guard is guaranteed to run.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order lookup, history and cancellation")
@PermissionResource(
        code = OrderResources.ORDERS,
        group = "Sales",
        label = "Orders",
        route = "/sales/orders",
        apiPath = "/api/v1/orders",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE, Action.EXPORT})
class OrderController {

    private final OrderService orderService;
    private final CustomerService customerService;
    private final CustomerOrders customerOrders;
    private final com.stockflow.order.internal.service.TransferInstructions transferInstructions;
    private final com.stockflow.identity.api.IdentityService identities;

    private final com.stockflow.order.internal.service.PartialShipments partialShipments;

    OrderController(OrderService orderService, CustomerService customerService,
                    CustomerOrders customerOrders,
                    com.stockflow.order.internal.service.TransferInstructions transferInstructions,
                    com.stockflow.identity.api.IdentityService identities,
                    com.stockflow.order.internal.service.PartialShipments partialShipments) {
        this.partialShipments = partialShipments;
        this.identities = identities;
        this.orderService = orderService;
        this.customerService = customerService;
        this.customerOrders = customerOrders;
        this.transferInstructions = transferInstructions;
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Look up one order")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<OrderResponse> findOne(@PathVariable UUID orderId,
                                              @AuthenticatedUser CurrentUser user) {
        UUID ownCustomerId = ownCustomerIdOrNull(user);
        return orderService.findById(orderId)
                // A customer sees only their own orders. The order carries the shipping address and
                // phone of whoever placed it, so answering 200 to anyone would leak both.
                .filter(order -> ownCustomerId == null || ownCustomerId.equals(order.customerId()))
                // 17 §5.2: a DRAFT order is Sales' work, not yet the customer's to see.
                .filter(order -> ownCustomerId == null || order.status() != OrderStatus.DRAFT)
                .map(order -> OrderWebMapper.toResponse(order, ownCustomerId == null))
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "No order with id " + orderId));
    }

    /**
     * Cancel an order and release its stock.
     *
     * <p>POST rather than DELETE: cancelling is a state transition that keeps the row and its
     * history, not a deletion. A DELETE here would suggest the order disappears, which no
     * accounting system would accept.</p>
     *
     * <p>SCRUM-242/WBS 3.17.4: this is the customer-initiated path — {@code scope = OWN} means a
     * caller can only reach their own order, enforced by {@code OrderRepository.findByIdInScope}.
     * An admin or sales rep cancelling any order with a documented reason uses
     * {@link #adminCancel} instead, gated by a different action so the two cannot be confused at
     * the permission layer.</p>
     */
    @PostMapping("/{orderId}/cancellation")
    @Operation(summary = "Cancel your order; once released to production or the warehouse, ask for it to be "
            + "cancelled (202, decided by Sales)")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.UPDATE, scope = DataScope.OWN)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public org.springframework.http.ResponseEntity<ApiResponse<CancellationResultResponse>> cancel(
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request,
            @RequestParam(value = "reason", required = false) String reason,
            @AuthenticatedUser CurrentUser user) {
        UUID ownCustomerId = ownCustomerIdOrNull(user);
        if (ownCustomerId == null) {
            // Staff on the customer path: an immediate cancellation, as before reason codes.
            CancelOrderCommand command = request != null && request.reasonCode() != null
                    ? new CancelOrderCommand(request.reasonCode(), request.note(), null, user.userId())
                    : CancelOrderCommand.fromText(reason == null ? "CUSTOMER_REQUEST" : reason, user.userId());
            var order = orderService.cancel(orderId, command);
            return org.springframework.http.ResponseEntity.ok(ApiResponse.ok(new CancellationResultResponse(
                    CancellationOutcome.Result.CANCELLED.name(), null, OrderWebMapper.toResponse(order))));
        }
        CancellationReasonCode code = request != null ? request.reasonCode() : null;
        String note = request != null ? request.note() : null;
        if (request == null && reason != null && !reason.isBlank()) {
            CancelOrderCommand legacy = CancelOrderCommand.fromText(reason, user.userId());
            code = legacy.reasonCode();
            note = legacy.note();
        }
        CancellationOutcome outcome = orderService.cancelOwn(orderId, ownCustomerId, code, note, user.userId());
        var body = ApiResponse.ok(new CancellationResultResponse(outcome.result().name(), outcome.requestId(),
                OrderWebMapper.toResponse(outcome.order(), false)));
        return outcome.result() == CancellationOutcome.Result.REQUESTED
                ? org.springframework.http.ResponseEntity.status(HttpStatus.ACCEPTED).body(body)
                : org.springframework.http.ResponseEntity.ok(body);
    }

    /**
     * Cancel any order as an admin or sales rep, with a documented reason.
     *
     * <p>SCRUM-242/WBS 3.17.4. Same underlying {@link OrderService#cancel}, but {@code scope =
     * ALL} and its own resource, {@code sales-order-cancellations:UPDATE}: kltn-docs 17 §2 has Sales
     * handle cancellations, while releasing an order to production ({@code sales-orders:APPROVE})
     * stays with the coordinator. A customer's role is never granted it, so this endpoint is
     * unreachable for them even though it accepts the same path shape as {@link #cancel}.</p>
     */
    @PostMapping("/{orderId}/admin-cancellation")
    @Operation(summary = "Cancel any order as an admin or sales rep, with a documented reason")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS,
            action = Action.UPDATE, scope = DataScope.ALL)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public ApiResponse<OrderResponse> adminCancel(@PathVariable UUID orderId,
                                                  @Valid @RequestBody AdminCancelOrderRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        CancelOrderCommand command;
        if (request.reasonCode() != null) {
            command = new CancelOrderCommand(request.reasonCode(), request.note(), request.retainedPercent(),
                    user.userId(), OrderWebMapper.toFee(request.agreedFee()));
        } else if (request.reason() != null && !request.reason().isBlank()) {
            CancelOrderCommand legacy = CancelOrderCommand.fromText(request.reason(), user.userId());
            command = new CancelOrderCommand(legacy.reasonCode(), legacy.note(), request.retainedPercent(),
                    user.userId(), OrderWebMapper.toFee(request.agreedFee()));
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reasonCode is required");
        }
        return ApiResponse.ok(OrderWebMapper.toResponse(orderService.cancel(orderId, command)));
    }

    /**
     * G3-2 (split shipments, G4-Q3): close what is left of a partly fulfilled order - the units not shipped will not
     * be delivered (kltn-docs 17 edge "short pick: phần còn lại huỷ + hoàn tiền phần thiếu"). Sales or the coordinator
     * decide it, as a cancellation: {@code sales-order-cancellations:UPDATE}. The money received above what the order
     * is now worth is asked back as PENDING refunds.
     */
    @PostMapping("/{orderId}/remainder-closure")
    @Operation(summary = "Close the rest of a partly fulfilled order: its unshipped units are not delivered; the "
            + "order shows its shipments and refunds what was paid for the rest")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS,
            action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> closeRemainder(@PathVariable UUID orderId,
                                                     @Valid @RequestBody CloseRemainderRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(OrderWebMapper.toResponse(orderService.closeRemainder(orderId, request.reasonCode(),
                request.note(), OrderWebMapper.toFee(request.agreedFee()), user.userId())));
    }

    /**
     * The back-office order list (SCRUM-443, FE 440). Staff only: a customer holds
     * {@code sales-orders:READ} too, for their own orders, and gets a 403 here rather than everyone's.
     * Parameters follow FE #12: {@code search}, {@code status} repeated, {@code sort=field,dir}.
     */
    @GetMapping
    @Operation(summary = "List orders for the back office: search, status, customer, placed date, sort")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<OrderResponse>> list(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String search,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) LocalDate placedFrom,
            @RequestParam(required = false) LocalDate placedTo,
            @RequestParam(required = false) String sort) {
        // Customers hold sales-orders:READ for their own orders; this list is every customer's.
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(orderService.list(new ListOrdersQuery(page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size, search, OrderStatusFilter.parse(statuses),
                        customerId,
                        placedFrom, placedTo, sort))
                .map(OrderWebMapper::toResponse));
    }

    @GetMapping("/{orderId}/history")
    @Operation(summary = "Status history of one order, oldest first")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<List<OrderStatusChangeResponse>> history(@PathVariable UUID orderId,
                                                                @AuthenticatedUser CurrentUser user) {
        UUID ownCustomerId = ownCustomerIdOrNull(user);
        orderService.findById(orderId)
                .filter(order -> ownCustomerId == null || ownCustomerId.equals(order.customerId()))
                .filter(order -> ownCustomerId == null || order.status() != OrderStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        List<com.stockflow.order.api.OrderStatusChange> changes = orderService.history(orderId);
        if (ownCustomerId != null) {
            // A customer reads why their order was cancelled; a hold's note and who did what are the shop's (G3-2).
            // Its drafting is not theirs either (17 §5.2): the steps into DRAFT are left out.
            return ApiResponse.ok(changes.stream().filter(change -> change.to() != OrderStatus.DRAFT)
                    .map(change -> new OrderStatusChangeResponse(change.from(), change.to(),
                            change.to() == OrderStatus.CANCELLED ? change.reason() : null, change.occurredAt(),
                            null, null))
                    .toList());
        }
        // PACK-PARTIAL: Sales' decisions on shipping in parts are part of the order's history for staff - entries of
        // their own type (PARTIAL_SHIPMENT_DECISION), not status changes.
        List<com.stockflow.order.api.PartialShipmentDecision> decisions = partialShipments.decisions(orderId);
        java.util.Map<UUID, String> names = identities.displayNames(java.util.stream.Stream.of(
                        changes.stream().map(com.stockflow.order.api.OrderStatusChange::actorId),
                        decisions.stream().map(com.stockflow.order.api.PartialShipmentDecision::decidedBy))
                .flatMap(stream -> stream)
                .filter(java.util.Objects::nonNull).distinct().toList());
        return ApiResponse.ok(java.util.stream.Stream.of(changes.stream()
                        .map(change -> new OrderStatusChangeResponse(change.from(), change.to(), change.reason(),
                                change.occurredAt(), change.actorId(),
                                change.actorId() == null ? null : names.get(change.actorId()))),
                decisions.stream().map(decision -> OrderStatusChangeResponse.partialShipmentDecision(
                        decision.allowed(), decision.note(), decision.decidedAt(), decision.decidedBy(),
                        decision.decidedBy() == null ? null : names.get(decision.decidedBy()))))
                .flatMap(stream -> stream)
                .sorted(java.util.Comparator.comparing(OrderStatusChangeResponse::occurredAt))
                .toList());
    }

    /**
     * SCRUM-245/WBS 3.17.7 and SCRUM-264. The signed-in customer's own orders as cards: one unified
     * status, the total, the line count and where delivery stands. Newest first unless {@code sort} says
     * otherwise; {@code status} may be repeated.
     *
     * <p>{@code customerId} is the customer profile of the sign-in, resolved here from
     * {@code @AuthenticatedUser}, never from a request parameter - there is deliberately no way to ask for
     * anyone else's orders through this endpoint. A signed-in account without a customer profile (staff)
     * gets 404 {@code CUSTOMER_NOT_FOUND}. The back office lists every order with {@link #list}.</p>
     */
    @GetMapping("/me")
    @Operation(summary = "List the signed-in customer's own orders: status filter, search, placed date, sort")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<CustomerOrderCardResponse>> myOrders(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LocalDate placedFrom,
            @RequestParam(required = false) LocalDate placedTo,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(customerOrders
                .myOrders(requiredOwnCustomerId(user), new CustomerOrders.Query(page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size, OrderStatusFilter.parse(statuses), search,
                        placedFrom, placedTo, sort))
                .map(CustomerOrderWebMapper::toResponse));
    }

    /**
     * One of the signed-in customer's own orders: status, timeline, what is printed, where delivery stands.
     * Someone else's order, or none, is 404 either way.
     *
     * <p>A path of its own rather than {@code /{orderId}}, which answers staff with the whole order: this
     * one never carries reservations, checksums, actors or the contact block.</p>
     */
    @GetMapping("/me/{orderId}")
    @Operation(summary = "Look up one of the signed-in customer's own orders, with its timeline")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<CustomerOrderDetailResponse> myOrder(@PathVariable UUID orderId,
                                                            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(CustomerOrderWebMapper.toResponse(
                customerOrders.myOrder(requiredOwnCustomerId(user), orderId)));
    }

    /**
     * SCRUM-193 (kltn-docs 15 §4.1 step 1): how to pay one of the signed-in customer's own orders by bank transfer -
     * the company's account, the order number to write as the content, and what is still due now. The customer is
     * resolved from the sign-in, never from the request; someone else's order, or none, is 404 either way, and an
     * account not configured for this deployment is 503 {@code TRANSFER_INSTRUCTIONS_UNAVAILABLE}.
     */
    @GetMapping("/me/{orderId}/transfer-instructions")
    @Operation(summary = "Bank transfer instructions for one of the signed-in customer's own orders: account, "
            + "transfer content (the order number) and the amount due now")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<TransferInstructionsResponse> myTransferInstructions(
            @PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(CustomerOrderWebMapper.toResponse(
                transferInstructions.of(requiredOwnCustomerId(user), orderId)));
    }

    /**
     * Orders are stored under the customer profile id, but the token identifies the sign-in account,
     * so "my orders" has to go through the profile: comparing an order's customer with the sign-in id
     * never matches once a customer has a profile of their own.
     *
     * <p>Data scope, not access: the endpoint's permission already decided the caller may act. The back office
     * acts on any customer's order; a portal account on its own customer's only.</p>
     *
     * @return null for the back office, who may see and cancel any order
     */
    private UUID ownCustomerIdOrNull(CurrentUser user) {
        if (ScopeGuards.isBackOffice(user)) {
            return null;
        }
        return requiredOwnCustomerId(user);
    }

    private UUID requiredOwnCustomerId(CurrentUser user) {
        return customerService.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}

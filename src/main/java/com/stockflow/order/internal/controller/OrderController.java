package com.stockflow.order.internal.controller;

import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.controller.dto.OrderStatusChangeResponse;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.internal.controller.dto.AdminCancelOrderRequest;
import com.stockflow.order.internal.controller.dto.CancelOrderRequest;
import com.stockflow.order.internal.controller.dto.CancellationResultResponse;
import com.stockflow.order.internal.controller.dto.CustomerOrderCardResponse;
import com.stockflow.order.internal.controller.dto.CustomerOrderDetailResponse;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.PlaceOrderRequest;
import com.stockflow.order.internal.controller.dto.QuoteRequests;
import com.stockflow.order.internal.controller.dto.QuoteResponse;
import com.stockflow.order.internal.service.Quotes;
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
import com.stockflow.common.security.Role;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** HTTP entry point for orders. Thin: request in, command out, response back. *
 * <p><b>Handler methods are public.</b> {@code @RequiresPermission} and {@code @Auditable} are
 * applied by Spring AOP proxies, which advise a package-private method only when the generated
 * proxy happens to land in the same package and classloader. When that does not hold the advice is
 * skipped silently - and a skipped {@code @RequiresPermission} is an endpoint with no authorisation
 * at all. Public is the only form where the guard is guaranteed to run.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Checkout, order lookup and cancellation")
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
    private final Quotes quotes;
    private final CustomerOrders customerOrders;

    OrderController(OrderService orderService, CustomerService customerService, Quotes quotes,
                    CustomerOrders customerOrders) {
        this.orderService = orderService;
        this.customerService = customerService;
        this.quotes = quotes;
        this.customerOrders = customerOrders;
    }

    /**
     * Place an order.
     *
     * <p>201 on success, 409 when stock ran out. The 409 comes from
     * {@code InsufficientStockException} propagating out of inventory, through the shared
     * exception handler, without this controller writing a single catch block — and, because the
     * whole call was one transaction, nothing partial is left behind when it happens.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Place an order: create it and reserve its stock in one transaction")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request,
                                            @AuthenticatedUser CurrentUser user) {
        boolean mayPlaceForAnotherCustomer = user.hasAnyRole(
                Role.SALES_STAFF, Role.ORDER_COORDINATOR, Role.ECOMMERCE_ADMIN);
        if (!mayPlaceForAnotherCustomer) {
            UUID ownCustomerId = requiredOwnCustomerId(user);
            if (!ownCustomerId.equals(request.customerId())) {
                // Hide whether another customer's id exists.
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
        }
        var command = OrderWebMapper.toCommand(request);
        if (!mayPlaceForAnotherCustomer && command.depositPercent() != null) {
            // The deposit share is the customer's terms, or a quote's (kltn-docs 15 §3) — never the
            // customer's own pick: their configured share is used.
            command = new com.stockflow.order.api.PlaceOrderCommand(command.requestId(), command.customerId(),
                    command.shippingAddressId(), command.billingAddressId(), command.snapshotCustomer(),
                    command.lines(), command.paymentTerm(), null);
        }
        return ApiResponse.ok(OrderWebMapper.toResponse(orderService.placeOrder(command)));
    }

    /**
     * Order again what a negotiated order bought (user decision 2026-10-12): a new DRAFT quote with the same
     * blanks, designs, print specifications and quantities, priced from today's price list - the old deal is
     * not reused and nothing is payable until Sales sends the quote and the customer accepts it. An order
     * bought at the price list is not re-quoted ({@code ORDER_NOT_QUOTED}): it is placed again directly.
     * The same verb as placing an order, so the customer and Sales may both start it.
     */
    @PostMapping("/{orderId}/reorder")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reorder a quoted order: a new draft quote for Sales at today's price list, never the old deal")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<QuoteResponse> reorder(@PathVariable UUID orderId,
                                              @Valid @RequestBody QuoteRequests.Reorder request,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.reorder(user,
                new Quotes.Reorder(request.requestId(), orderId))));
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
            @RequestParam(name = "status", required = false) List<OrderStatus> statuses,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) LocalDate placedFrom,
            @RequestParam(required = false) LocalDate placedTo,
            @RequestParam(required = false) String sort) {
        requireStaff(user);
        return ApiResponse.ok(orderService.list(new ListOrdersQuery(page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size, search, statuses, customerId,
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
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        return ApiResponse.ok(orderService.history(orderId).stream()
                .map(change -> new OrderStatusChangeResponse(change.from(), change.to(), change.reason(),
                        change.occurredAt()))
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
            @RequestParam(name = "status", required = false) List<OrderStatus> statuses,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LocalDate placedFrom,
            @RequestParam(required = false) LocalDate placedTo,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(customerOrders
                .myOrders(requiredOwnCustomerId(user), new CustomerOrders.Query(page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size, statuses, search, placedFrom, placedTo, sort))
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
     * Orders are stored under the customer profile id, but the token identifies the sign-in account,
     * so "my orders" has to go through the profile: comparing an order's customer with the sign-in id
     * never matches once a customer has a profile of their own.
     *
     * @return null for staff, who may see and cancel any order
     */
    private static void requireStaff(CurrentUser user) {
        if (!user.hasAnyRole(Role.SALES_STAFF, Role.ORDER_COORDINATOR, Role.ECOMMERCE_ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "The order list is for back-office staff");
        }
    }

    private UUID ownCustomerIdOrNull(CurrentUser user) {
        if (user.hasAnyRole(Role.SALES_STAFF, Role.ORDER_COORDINATOR, Role.ECOMMERCE_ADMIN)) {
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

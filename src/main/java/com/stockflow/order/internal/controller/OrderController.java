package com.stockflow.order.internal.controller;

import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.controller.dto.AdminCancelOrderRequest;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.PlaceOrderRequest;
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

    OrderController(OrderService orderService) {
        this.orderService = orderService;
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
    public ApiResponse<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
        return ApiResponse.ok(OrderWebMapper.toResponse(
                orderService.placeOrder(OrderWebMapper.toCommand(request))));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Look up one order")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<OrderResponse> findOne(@PathVariable UUID orderId) {
        return orderService.findById(orderId)
                .map(OrderWebMapper::toResponse)
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
    @Operation(summary = "Cancel an order and release its reservations")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.UPDATE, scope = DataScope.OWN)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public ApiResponse<Void> cancel(@PathVariable UUID orderId,
                             @RequestParam(value = "reason", required = false) String reason) {
        orderService.cancel(orderId, reason == null ? "CUSTOMER_REQUEST" : reason);
        return ApiResponse.ok(null);
    }

    /**
     * Cancel any order as an admin or sales rep, with a documented reason.
     *
     * <p>SCRUM-242/WBS 3.17.4. Same underlying {@link OrderService#cancel}, but {@code scope =
     * ALL} and a distinct action ({@code APPROVE}, not {@code UPDATE}) — a customer's role is never
     * granted {@code APPROVE} on this resource, so this endpoint is unreachable for them even
     * though it accepts the same path shape as {@link #cancel}.</p>
     */
    @PostMapping("/{orderId}/admin-cancellation")
    @Operation(summary = "Cancel any order as an admin or sales rep, with a documented reason")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.APPROVE, scope = DataScope.ALL)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public ApiResponse<Void> adminCancel(@PathVariable UUID orderId,
                             @Valid @RequestBody AdminCancelOrderRequest request) {
        orderService.cancel(orderId, request.reason());
        return ApiResponse.ok(null);
    }

    /**
     * SCRUM-245/WBS 3.17.7. The signed-in customer's own order history, newest first, paginated.
     *
     * <p>{@code customerId} comes from {@code @AuthenticatedUser}, never from a request parameter —
     * there is deliberately no way to ask for anyone else's history through this endpoint. The
     * assumption this rests on — that the authenticated principal's id equals a row in {@code
     * customer.customer} — does not hold for anything in this codebase today: {@code identity}'s
     * accounts are staff/backoffice logins, and no customer-facing authentication or
     * identity-to-customer linkage exists yet (see SCRUM-46). This endpoint is correct for the
     * moment that linkage exists; it is not meaningfully callable by a real customer before then.</p>
     */
    @GetMapping("/me")
    @Operation(summary = "List the signed-in customer's own orders")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<OrderResponse>> myOrders(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(orderService
                .myOrders(user.userId(), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(OrderWebMapper::toResponse));
    }
}

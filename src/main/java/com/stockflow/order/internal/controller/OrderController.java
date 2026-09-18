package com.stockflow.order.internal.controller;

import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.PlaceOrderRequest;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
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
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.EXPORT})
class OrderController {

    private final OrderService orderService;
    private final CustomerService customerService;

    OrderController(OrderService orderService, CustomerService customerService) {
        this.orderService = orderService;
        this.customerService = customerService;
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
            UUID ownCustomerId = customerService.findByUserId(user.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                    .customerId();
            if (!ownCustomerId.equals(request.customerId())) {
                // Hide whether another customer's id exists.
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
        }
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
     */
    @PostMapping("/{orderId}/cancellation")
    @Operation(summary = "Cancel an order and release its reservations")
    @RequiresPermission(resource = OrderResources.ORDERS,
            action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<Void> cancel(@PathVariable UUID orderId,
                             @RequestParam(value = "reason", required = false) String reason) {
        orderService.cancel(orderId, reason == null ? "CUSTOMER_REQUEST" : reason);
        return ApiResponse.ok(null);
    }
}

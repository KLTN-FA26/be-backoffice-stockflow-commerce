package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.controller.dto.CreditDecisionRequest;
import com.stockflow.order.internal.controller.dto.CreditHoldResponse;
import com.stockflow.order.internal.controller.dto.CreditPositionResponse;
import com.stockflow.order.internal.controller.dto.CreditRefusalRequest;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.service.CreditHoldService;
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

import java.util.Arrays;
import java.util.UUID;

/**
 * Credit at the order (SCRUM-427, SCRUM-193; kltn-docs 15 §4.3, BR-01, BR-03): what a customer may be
 * sold on and the credit left, and the queue of orders over the limit that whoever approves credit
 * confirms or refuses.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@PermissionResource(code = OrderResources.CREDIT_HOLDS, group = "Sales", label = "Orders over the credit limit",
        route = "/sales/orders/credit-holds", apiPath = "/api/v1/orders/credit-holds",
        actions = {Action.VIEW_PAGE, Action.READ, Action.APPROVE})
class CreditHoldController {

    private final CreditHoldService credit;
    private final CustomerService customers;
    private final com.stockflow.order.api.OrderService orders;
    private final com.stockflow.order.internal.domain.CreditChecks checks;

    CreditHoldController(CreditHoldService credit, CustomerService customers,
                         com.stockflow.order.api.OrderService orders,
                         com.stockflow.order.internal.domain.CreditChecks checks) {
        this.credit = credit;
        this.customers = customers;
        this.orders = orders;
        this.checks = checks;
    }

    @GetMapping("/payment-terms")
    @Operation(summary = "The signed-in customer's payment terms and credit left, for the checkout")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<CreditPositionResponse> myTerms(@AuthenticatedUser CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
        return ApiResponse.ok(toResponse(credit.position(customerId)));
    }

    @GetMapping("/credit-position/{customerId}")
    @Operation(summary = "A customer's payment terms, what they owe on credit and what is left")
    @RequiresPermission(resource = "customer-credit-profiles", action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<CreditPositionResponse> position(@PathVariable UUID customerId) {
        return ApiResponse.ok(toResponse(credit.position(customerId)));
    }

    @GetMapping("/credit-holds")
    @Operation(summary = "Orders over their customer's credit limit, oldest first")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CreditHoldResponse>> holds(@RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(credit.heldOrders(page, size == null ? com.stockflow.common.persistence.Pages.DEFAULT_PAGE_SIZE : size)
                .map(id -> {
                    OrderResponse order = OrderWebMapper.toResponse(orders.findById(id).orElseThrow());
                    var check = checks.latest(id).orElseThrow();
                    return new CreditHoldResponse(order, check.checkedAt(), check.creditLimit(), check.exposure(),
                            check.orderAmount());
                }));
    }

    @PostMapping("/{orderId}/credit-approval")
    @Operation(summary = "Confirm an order over the credit limit; the decision is recorded with its reason")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> approve(@PathVariable UUID orderId,
                                              @Valid @RequestBody CreditDecisionRequest request,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(credit.approve(orderId, request.note(), user.userId())));
    }

    @PostMapping("/{orderId}/credit-rejection")
    @Operation(summary = "Refuse: cancel the order, or switch it to prepaid or deposit terms the customer may use")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> refuse(@PathVariable UUID orderId,
                                             @Valid @RequestBody CreditRefusalRequest request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(credit.refuse(orderId, request.action(),
                request.depositPercent(), request.note(), user.userId())));
    }

    private static CreditPositionResponse toResponse(CreditHoldService.CreditPosition p) {
        var t = p.terms();
        return new CreditPositionResponse(t.customerId(),
                Arrays.stream(CommercialTerm.values()).filter(t::allows).map(Enum::name).toList(),
                t.defaultTerm().name(), t.depositPercent(), t.creditLimit(), t.creditTermDays(), p.exposure(),
                p.available(), t.currency(), p.hasOverdue());
    }
}

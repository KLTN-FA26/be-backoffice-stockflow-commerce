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
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.controller.dto.ContractCreditReviewResponse;
import com.stockflow.order.internal.controller.dto.CreditDecisionRequest;
import com.stockflow.order.internal.controller.dto.CreditHoldResponse;
import com.stockflow.order.internal.controller.dto.CreditPositionResponse;
import com.stockflow.order.internal.controller.dto.CreditRefusalRequest;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.SalesContractRequests;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.SalesContracts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * Credit at the order (SCRUM-427, SCRUM-193; kltn-docs 15 §4.3, BR-10, BR-11; 18 §2): a customer's credit and what is
 * left of it - for Sales, and for the customer on the portal - and the queue of orders held for credit at their
 * contract's signature that accounting confirms or refuses. Since 2026-11-26 (15 BR-11) also the contracts whose credit
 * check failed when Sales sent them, which wait here for accounting before the customer sees them.
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
    /** 15 BR-11 (2026-11-26): the contracts waiting for accounting before they are sent. */
    private final SalesContracts contracts;

    CreditHoldController(CreditHoldService credit, CustomerService customers,
                         com.stockflow.order.api.OrderService orders,
                         com.stockflow.order.internal.domain.CreditChecks checks, SalesContracts contracts) {
        this.contracts = contracts;
        this.credit = credit;
        this.customers = customers;
        this.orders = orders;
        this.checks = checks;
    }

    /**
     * The signed-in customer's own credit (15 §2, 18 §2: "vị thế tín dụng"). Replaces {@code GET /payment-terms}
     * (4B-10): there are no payment terms to offer, only the credit left.
     */
    @GetMapping("/credit-position/me")
    @Operation(summary = "The signed-in customer's credit limit, what they owe after delivery and what is left")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<CreditPositionResponse> myCredit(@AuthenticatedUser CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
        return ApiResponse.ok(toResponse(credit.position(customerId)));
    }

    @GetMapping("/credit-position/{customerId}")
    @Operation(summary = "A customer's credit limit, what they owe after delivery and what is left")
    @RequiresPermission(resource = "customer-credit-profiles", action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<CreditPositionResponse> position(@PathVariable UUID customerId) {
        return ApiResponse.ok(toResponse(credit.position(customerId)));
    }

    /**
     * Audit bo-29: the credit of a page of customers, for the back office customer list's optional credit columns. The
     * customer module cannot read what a customer owes (payment depends on customer: the other way would be a cycle),
     * so the list calls this once per page beside {@code GET /customers}, with the page's ids. Each item is what
     * {@code /credit-position/{customerId}} answers for that customer, under the same grant: the credit limit is
     * accounting's figure and what a customer owes is not every customer reader's business, so a role that reads the
     * list but not credit gets 403 here and the list shows no credit columns.
     *
     * <p>At most a page of the list ({@value com.stockflow.common.persistence.Pages#MAX_PAGE_SIZE}), so the read stays
     * two statements of bounded size.</p>
     */
    @GetMapping("/credit-positions")
    @Operation(summary = "The credit of many customers at once: limit, exposure, what is left and what is overdue, "
            + "for each customer asked",
            description = "Needs customer-credit-profiles:READ, as /credit-position/{customerId}. One item per distinct "
                    + "customer that exists, in the order asked; an unknown id is left out. Two reads whatever the "
                    + "number of customers. 400 UNSUPPORTED_PARAMETER past 200 ids.")
    @RequiresPermission(resource = "customer-credit-profiles", action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<CreditPositionResponse>> positions(
            @AuthenticatedUser CurrentUser user,
            @Parameter(description = "The customers, comma separated (customerIds=a,b,c) or the parameter "
                    + "repeated (customerIds=a&customerIds=b), not both: typically the ids of one page of GET /customers; "
                    + "at most 200")
            @RequestParam List<UUID> customerIds) {
        ScopeGuards.requireBackOffice(user);
        if (customerIds.size() > com.stockflow.common.persistence.Pages.MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "At most %d customers, got %d".formatted(
                    com.stockflow.common.persistence.Pages.MAX_PAGE_SIZE, customerIds.size()));
        }
        return ApiResponse.ok(credit.positions(customerIds).stream().map(CreditHoldController::toResponse).toList());
    }

    @GetMapping("/credit-holds")
    @Operation(summary = "Orders over their customer's credit limit, oldest first")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CreditHoldResponse>> holds(@RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(required = false) Integer size) {
        var held = credit.heldOrders(page, size == null ? com.stockflow.common.persistence.Pages.DEFAULT_PAGE_SIZE : size);
        var found = held.items().stream().map(id -> orders.findById(id).orElseThrow()).toList();
        // The accountant who decides these holds has no customer permission: name each order's customer from one
        // query for the page - not one per row.
        var labels = customers.labels(found.stream().map(com.stockflow.order.api.OrderSummary::customerId).toList());
        var byId = new java.util.HashMap<UUID, com.stockflow.order.api.OrderSummary>();
        found.forEach(order -> byId.put(order.orderId(), order));
        return ApiResponse.ok(held.map(id -> {
            var summary = byId.get(id);
            OrderResponse order = OrderWebMapper.toResponse(summary,
                    OrderWebMapper.labelOf(labels, summary.customerId()));
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
    @Operation(summary = "Refuse: the order is cancelled (CREDIT_REJECTED); the decision is recorded with its reason")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> refuse(@PathVariable UUID orderId,
                                             @Valid @RequestBody CreditRefusalRequest request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(credit.refuse(orderId, request.note(), user.userId())));
    }

    @GetMapping("/contract-credit-reviews")
    @Operation(summary = "Contracts not sent because their credit check failed, waiting for accounting, oldest first "
            + "(15 BR-11, 2026-11-26): the figures as they were when Sales sent it, and the contract's version to "
            + "approve or refuse it with")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<ContractCreditReviewResponse>> contractCreditReviews(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        var waiting = contracts.creditReviewsWaiting(page,
                size == null ? com.stockflow.common.persistence.Pages.DEFAULT_PAGE_SIZE : size);
        // As the credit holds: the accountant has no customer permission, so the page's customers are named in one go.
        var labels = customers.labels(waiting.items().stream().map(SalesContracts.CreditReviewWaiting::customerId)
                .toList());
        return ApiResponse.ok(waiting.map(w -> new ContractCreditReviewResponse(w.reviewId(), w.orderId(),
                w.orderNumber(), w.contractId(), w.contractNumber(), w.contractVersion(), w.customerId(),
                OrderWebMapper.labelOf(labels, w.customerId()), w.checkOutcome(), w.creditLimit(), w.exposure(),
                w.afterDelivery(), w.overdueAmount(), w.submittedBy(), w.submittedAt())));
    }

    @PostMapping("/{orderId}/contract/credit-review/approval")
    @Operation(summary = "Approve the credit of a contract waiting, with a note: it is sent to the customer at once, "
            + "as the Sales account that asked (15 BR-11, 2026-11-26). 409 CONTRACT_CREDIT_REVIEW_NOT_PENDING; 403 "
            + "CONTRACT_CREDIT_REVIEW_SELF for whoever sent it for review; 409 OPTIMISTIC_LOCK for a stale version")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<SalesContracts.ContractView> approveContractCredit(
            @PathVariable UUID orderId, @Valid @RequestBody SalesContractRequests.CreditDecision request,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.approveCredit(user, orderId, request.version(), request.note()));
    }

    @PostMapping("/{orderId}/contract/credit-review/rejection")
    @Operation(summary = "Refuse the credit of a contract waiting, with the reason: it stays a DRAFT for Sales to "
            + "change the payment plan and send again; nothing is cancelled (15 BR-11, 2026-11-26)")
    @RequiresPermission(resource = OrderResources.CREDIT_HOLDS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<SalesContracts.ContractView> refuseContractCredit(
            @PathVariable UUID orderId, @Valid @RequestBody SalesContractRequests.CreditDecision request,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.refuseCredit(user, orderId, request.version(), request.note()));
    }

    private static CreditPositionResponse toResponse(CreditHoldService.CreditPosition p) {
        var t = p.terms();
        return new CreditPositionResponse(t.customerId(), t.creditLimit(), t.creditTermDays(), p.exposure(),
                p.available(), t.currency(), p.hasOverdue(), p.overdueAmount(), p.oldestOverdueDueDate());
    }
}

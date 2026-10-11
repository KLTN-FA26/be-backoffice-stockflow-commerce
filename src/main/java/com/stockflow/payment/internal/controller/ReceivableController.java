package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
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
import com.stockflow.payment.internal.controller.dto.AllocationResponse;
import com.stockflow.payment.internal.controller.dto.ReceivableResponse;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import com.stockflow.payment.internal.service.ReceivableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What customers owe on delivered credit orders (SCRUM-431, kltn-docs 15 §4.3, §5.3): the accountant's
 * and Sales' list, and a customer's own (18 §2: "xem công nợ").
 */
@RestController
@RequestMapping("/api/v1/receivables")
@Tag(name = "Receivables")
@PermissionResource(code = PaymentResources.RECEIVABLES, group = "Payment", label = "Receivables",
        route = "/payment/receivables", apiPath = "/api/v1/receivables",
        actions = {Action.VIEW_PAGE, Action.READ, Action.EXPORT})
class ReceivableController {

    private final ReceivableService receivables;
    private final CustomerService customers;

    ReceivableController(ReceivableService receivables, CustomerService customers) {
        this.receivables = receivables;
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "Receivables, earliest due first; filter by customer, status and due date")
    @RequiresPermission(resource = PaymentResources.RECEIVABLES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<ReceivableResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) ReceivableStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser CurrentUser user) {
        // Customers hold payment-receivables:READ for /me; this list is every customer's.
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(receivables.receivables(customerId, status, dueBefore, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(r -> toResponse(r, null)));
    }

    @GetMapping("/me")
    @Operation(summary = "What the signed-in customer owes")
    @RequiresPermission(resource = PaymentResources.RECEIVABLES, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<ReceivableResponse>> mine(
            @RequestParam(required = false) ReceivableStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
        return ApiResponse.ok(receivables.receivables(customerId, status, null, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, null).map(r -> toResponse(r, null)));
    }

    @GetMapping("/{receivableId}")
    @Operation(summary = "One receivable, with the transfers that paid it")
    @RequiresPermission(resource = PaymentResources.RECEIVABLES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ReceivableResponse> one(@PathVariable UUID receivableId,
                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Receivable receivable = receivables.receivable(receivableId);
        return ApiResponse.ok(toResponse(receivable, receivables.allocationsOf(receivableId)));
    }

    static ReceivableResponse toResponse(Receivable r, List<ReceivableBook.Allocation> allocations) {
        return new ReceivableResponse(r.id(), r.orderId(), r.customerId(), r.amount(), r.paidAmount(), r.outstanding(),
                r.currency(), r.issuedAt(), r.dueDate(), r.status().name(), r.settledAt(),
                allocations == null ? null : allocations.stream().map(ReceivableController::toResponse).toList());
    }

    static AllocationResponse toResponse(ReceivableBook.Allocation a) {
        return new AllocationResponse(a.id(), a.transferId(), a.receivableId(), a.amount(), a.allocatedAt(),
                a.allocatedBy());
    }
}

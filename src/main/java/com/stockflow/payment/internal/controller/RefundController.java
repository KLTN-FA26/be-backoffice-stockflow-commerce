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
import com.stockflow.payment.internal.controller.dto.OrderRefundResponse;
import com.stockflow.payment.internal.controller.dto.RecordRefundRequest;
import com.stockflow.payment.internal.controller.dto.RefundRecordResponse;
import com.stockflow.payment.internal.controller.dto.VoidRefundRecordRequest;
import com.stockflow.payment.internal.domain.RefundRecord;
import com.stockflow.payment.internal.service.RefundService;
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

/**
 * Refunds of contract orders (4B-9, kltn-docs 15 §4.5, §5.3, BR-14; D15): what a cancellation or a closure of the rest
 * left to refund, and the accountant's record of each refund made outside the system - never more than what is left,
 * voided with a reason when wrong. The customer sees their order's refund on the portal (17 §5.2).
 */
@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "Refunds")
@PermissionResource(code = PaymentResources.REFUNDS, group = "Payment", label = "Refunds", route = "/payment/refunds",
        apiPath = "/api/v1/refunds", actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class RefundController {

    private final RefundService refunds;
    private final CustomerService customers;

    RefundController(RefundService refunds, CustomerService customers) {
        this.refunds = refunds;
        this.customers = customers;
    }

    /** The accountant's work list: orders with something to refund ({@code outstanding=true}: not refunded in full). */
    @GetMapping
    @Operation(summary = "Orders with money to refund, most recently settled first")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<OrderRefundResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) Boolean outstanding,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(refunds.orderRefunds(customerId, outstanding, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(PaymentWebMapper::toResponse));
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "An order's refund: what is to refund, what was refunded, and why")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<OrderRefundResponse> ofOrder(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(PaymentWebMapper.toResponse(refunds.detail(orderId, null), false));
    }

    /** 15 §4.5 step 16: refunded outside the system, recorded here; at most what is still to refund (BR-14). */
    @PostMapping("/orders/{orderId}/records")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a refund made to the customer for the order")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<OrderRefundResponse> record(@PathVariable UUID orderId,
                                                   @Valid @RequestBody RecordRefundRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(PaymentWebMapper.toResponse(refunds.record(new RefundService.RecordRefundCommand(orderId,
                request.amount(), request.refundedOn(), request.reference(), request.note(), user.userId())), false));
    }

    @GetMapping("/records")
    @Operation(summary = "Refund records, newest first; filter by customer, order and status")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<RefundRecordResponse>> records(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) RefundRecord.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(refunds.records(customerId, orderId, status, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(PaymentWebMapper::toResponse));
    }

    /** 15 §4.5 step 16: a record made wrong is voided with a reason and kept; its amount is to refund again. */
    @PostMapping("/records/{recordId}/void")
    @Operation(summary = "Void a refund record made wrong")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderRefundResponse> voidRecord(@PathVariable UUID recordId,
                                                       @Valid @RequestBody VoidRefundRecordRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(PaymentWebMapper.toResponse(refunds.voidRecord(recordId, request.reason(),
                user.userId()), false));
    }

    /** 17 §5.2: the customer follows their cancelled order's refund on the portal. */
    @GetMapping("/me/orders/{orderId}")
    @Operation(summary = "The refund of one of the signed-in customer's orders")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<OrderRefundResponse> myOrder(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
        return ApiResponse.ok(PaymentWebMapper.toResponse(refunds.detail(orderId, customerId), true));
    }
}

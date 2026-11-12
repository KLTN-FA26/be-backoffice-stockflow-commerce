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
import com.stockflow.payment.internal.controller.dto.InstallmentResponse;
import com.stockflow.payment.internal.controller.dto.InstallmentTransferResponse;
import com.stockflow.payment.internal.controller.dto.OrderInstallmentsResponse;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.service.InstallmentService;
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
 * The installments of signed contracts (4B-6, kltn-docs 15 §2, §4.1-§4.3, §5.1, §5.2): the accountant's list of what is
 * due and owed, Sales' view of an order's installments and the transfer details to send the customer, and the
 * customer's own installments and how to pay each.
 *
 * <p>What the customer owes after delivery (15 §4.3 step 8, "công nợ phải thu") is
 * {@code ?milestone=AFTER_DELIVERY&reached=true}, next to the money ledger's {@code /receivables} until 4B-10.</p>
 */
@RestController
@RequestMapping("/api/v1/installments")
@Tag(name = "Installments")
@PermissionResource(code = PaymentResources.INSTALLMENTS, group = "Payment", label = "Installments",
        route = "/payment/installments", apiPath = "/api/v1/installments",
        actions = {Action.VIEW_PAGE, Action.READ})
class InstallmentController {

    private final InstallmentService installments;
    private final CustomerService customers;

    InstallmentController(InstallmentService installments, CustomerService customers) {
        this.installments = installments;
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "Installments, earliest due first; filter by customer, order, status, milestone, due day, "
            + "whether their milestone came and whether they are overdue (the aged-receivables reading: overdue is a "
            + "flag worked out from the due day, not a status)")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<InstallmentResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) InstallmentStatus status,
            @RequestParam(required = false) InstallmentMilestone milestone,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
            @RequestParam(required = false) Boolean reached,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser CurrentUser user) {
        // Customers hold payment-installments:READ for /me; this list is every customer's.
        ScopeGuards.requireBackOffice(user);
        LocalDate today = installments.today();
        return ApiResponse.ok(installments.installments(customerId, orderId, status, milestone, dueBefore, reached,
                overdue, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                .map(i -> PaymentWebMapper.toResponse(i, null, today)));
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in customer's installments")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<InstallmentResponse>> mine(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) InstallmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        LocalDate today = installments.today();
        return ApiResponse.ok(installments.installments(customerOf(user), orderId, status, null, null, null, null,
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, null)
                .map(i -> PaymentWebMapper.toResponse(i, null, today)));
    }

    @GetMapping("/me/orders/{orderId}")
    @Operation(summary = "The installments of one of the signed-in customer's orders, and where they stand")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<OrderInstallmentsResponse> myOrder(@PathVariable UUID orderId,
                                                          @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(PaymentWebMapper.toResponse(installments.ofOrder(orderId, customerOf(user)),
                installments.today()));
    }

    @GetMapping("/me/{installmentId}")
    @Operation(summary = "One of the signed-in customer's installments, with the receipts that paid it")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<InstallmentResponse> myOne(@PathVariable UUID installmentId,
                                                  @AuthenticatedUser CurrentUser user) {
        var detail = installments.detail(installmentId, customerOf(user));
        // A voided receipt is the accountant's correction, not the customer's payment.
        List<InstallmentBook.ReceiptOfInstallment> live = detail.receipts().stream()
                .filter(r -> r.status() == PaymentReceiptStatus.RECORDED).toList();
        return ApiResponse.ok(PaymentWebMapper.toResponse(detail.installment(), live, installments.today()));
    }

    @GetMapping("/me/{installmentId}/transfer-instructions")
    @Operation(summary = "How the signed-in customer pays one of their installments by bank transfer")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<InstallmentTransferResponse> myTransferInstructions(@PathVariable UUID installmentId,
                                                                           @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(PaymentWebMapper.toResponse(
                installments.transferInstructions(installmentId, customerOf(user))));
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "An order's installments and where they stand (15 §5.2)")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<OrderInstallmentsResponse> ofOrder(@PathVariable UUID orderId,
                                                          @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(PaymentWebMapper.toResponse(installments.ofOrder(orderId, null),
                installments.today()));
    }

    @GetMapping("/{installmentId}")
    @Operation(summary = "One installment, with the receipts that paid it, voided ones included")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<InstallmentResponse> one(@PathVariable UUID installmentId,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var detail = installments.detail(installmentId, null);
        return ApiResponse.ok(PaymentWebMapper.toResponse(detail.installment(), detail.receipts(),
                installments.today()));
    }

    /** 15 §2, 16: the transfer details Sales sends the customer for one installment. */
    @GetMapping("/{installmentId}/transfer-instructions")
    @Operation(summary = "How the customer pays one installment by bank transfer")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<InstallmentTransferResponse> transferInstructions(@PathVariable UUID installmentId,
                                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(PaymentWebMapper.toResponse(installments.transferInstructions(installmentId, null)));
    }

    private UUID customerOf(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}

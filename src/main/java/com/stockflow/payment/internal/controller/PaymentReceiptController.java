package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse;
import com.stockflow.payment.internal.controller.dto.RecordReceiptRequest;
import com.stockflow.payment.internal.controller.dto.VoidReceiptRequest;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.service.PaymentReceiptService;
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
 * Payment receipts (4B-6, kltn-docs 15 §4.2, §4.4, §5.3): the accountant records what the bank statement shows against
 * the installments it pays (BR-02, BR-05), once per statement line (BR-03), and voids one recorded wrong - never edits
 * it (BR-04).
 */
@RestController
@RequestMapping("/api/v1/payment-receipts")
@Tag(name = "Payment receipts")
@PermissionResource(code = PaymentResources.RECEIPTS, group = "Payment", label = "Payment receipts",
        route = "/payment/receipts", apiPath = "/api/v1/payment-receipts",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class PaymentReceiptController {

    private final PaymentReceiptService receipts;

    PaymentReceiptController(PaymentReceiptService receipts) {
        this.receipts = receipts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a payment receipt against installments of one customer")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<PaymentReceiptResponse> record(@Valid @RequestBody RecordReceiptRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var detail = receipts.record(new PaymentReceiptService.RecordReceiptCommand(request.customerId(),
                request.reference(), request.receivedOn(), request.note(), request.lines().stream()
                .map(line -> new PaymentReceipt.Payment(line.installmentId(), line.amount())).toList(),
                user.userId()));
        return ApiResponse.ok(PaymentWebMapper.toResponse(detail.receipt(), detail.installments()));
    }

    @GetMapping
    @Operation(summary = "Payment receipts, latest first; filter by customer, status and statement reference")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<PaymentReceiptResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) PaymentReceiptStatus status,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(receipts.receipts(customerId, status, reference, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(r -> PaymentWebMapper.toResponse(r, null)));
    }

    @GetMapping("/{receiptId}")
    @Operation(summary = "One payment receipt, with the installments it pays")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PaymentReceiptResponse> one(@PathVariable UUID receiptId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var detail = receipts.receipt(receiptId);
        return ApiResponse.ok(PaymentWebMapper.toResponse(detail.receipt(), detail.installments()));
    }

    /**
     * Void a receipt recorded wrong (15 §4.4): kept with who, when and why. A confirmed order it leaves short of its
     * installment on signing is refused unless {@code holdOrders} agrees to put it on hold (BR-13).
     */
    @PostMapping("/{receiptId}/void")
    @Operation(summary = "Void a payment receipt recorded wrong")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<PaymentReceiptResponse> voidReceipt(@PathVariable UUID receiptId,
                                                           @Valid @RequestBody VoidReceiptRequest request,
                                                           @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var detail = receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(receiptId, request.reason(),
                request.holdOrders(), user.userId()));
        return ApiResponse.ok(PaymentWebMapper.toResponse(detail.receipt(), detail.installments()));
    }
}

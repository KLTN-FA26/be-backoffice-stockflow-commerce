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
import com.stockflow.payment.internal.controller.dto.AllocateTransferRequest;
import com.stockflow.payment.internal.controller.dto.CreditRefundRequest;
import com.stockflow.payment.internal.controller.dto.CreditRefundResponse;
import com.stockflow.payment.internal.controller.dto.CustomerTransferResponse;
import com.stockflow.payment.internal.controller.dto.RecordTransferRequest;
import com.stockflow.payment.internal.controller.dto.ReverseAllocationRequest;
import com.stockflow.payment.internal.service.MoneyLedgerService;
import com.stockflow.payment.internal.service.ReceivableService;
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

import java.util.List;
import java.util.UUID;

/**
 * The ledger of incoming money (SCRUM-431; G2-3, decision G3-Q3: one ledger), kept by the accountant from the bank
 * statement (kltn-docs 15 §2, BR-04, BR-07): transfers, what they pay - orders (prepaid, deposit, the balance before
 * delivery) and receivables (BR-09) - and the customer's credit left from them. Every movement is a new row (BR-06).
 */
@RestController
@RequestMapping("/api/v1/customer-transfers")
@Tag(name = "Receivables")
@PermissionResource(code = PaymentResources.CUSTOMER_TRANSFERS, group = "Payment", label = "Customer transfers",
        route = "/payment/customer-transfers", apiPath = "/api/v1/customer-transfers",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class CustomerTransferController {

    private final ReceivableService receivables;
    private final MoneyLedgerService ledger;

    CustomerTransferController(ReceivableService receivables, MoneyLedgerService ledger) {
        this.receivables = receivables;
        this.ledger = ledger;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a transfer from the bank statement; it pays the orders named (each the amount given), "
            + "then the receivables named - or, when nothing is named, the earliest due first - and the rest stays as "
            + "the customer's credit")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<CustomerTransferResponse> record(@Valid @RequestBody RecordTransferRequest request,
                                                        @AuthenticatedUser CurrentUser user) {
        var result = receivables.recordTransfer(new ReceivableService.RecordTransferCommand(request.customerId(),
                request.reference(), request.amount(), request.currency(), request.receivedOn(), request.allocateTo(),
                request.note(), user.userId(), request.orders() == null ? List.of() : request.orders().stream()
                .map(o -> new ReceivableService.OrderPayment(o.orderId(), o.amount())).toList()));
        return ApiResponse.ok(PaymentWebMapper.toResponse(result.transfer(), result.allocations()));
    }

    @GetMapping
    @Operation(summary = "Recorded transfers, newest first; withCredit=true lists only money not yet allocated")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CustomerTransferResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "false") boolean withCredit,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(receivables.transfers(customerId, withCredit, page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                .map(t -> PaymentWebMapper.toResponse(t, null)));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "One transfer, with every allocation and reversal of its money")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<CustomerTransferResponse> one(@PathVariable UUID transferId) {
        var detail = ledger.transferDetail(transferId);
        return ApiResponse.ok(PaymentWebMapper.toResponse(detail.transfer(), detail.allocations()));
    }

    @PostMapping("/{transferId}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Pay part of the transfer's unallocated money to an order or a receivable")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CustomerTransferResponse> allocate(@PathVariable UUID transferId,
                                                          @Valid @RequestBody AllocateTransferRequest request,
                                                          @AuthenticatedUser CurrentUser user) {
        var result = ledger.allocate(new MoneyLedgerService.AllocateCommand(transferId, request.orderId(),
                request.receivableId(), request.amount(), user.userId()));
        return ApiResponse.ok(PaymentWebMapper.toResponse(result.transfer(), List.of(result.allocation())));
    }

    @PostMapping("/{transferId}/allocations/{allocationId}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Take an allocation back (a reversal row); the money is the customer's credit again. A "
            + "confirmed order left short of its terms needs holdOrder=true and goes ON_HOLD")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CustomerTransferResponse> reverse(@PathVariable UUID transferId, @PathVariable UUID allocationId,
                                                         @Valid @RequestBody ReverseAllocationRequest request,
                                                         @AuthenticatedUser CurrentUser user) {
        var result = ledger.reverse(new MoneyLedgerService.ReverseCommand(transferId, allocationId, request.reason(),
                request.holdOrder(), user.userId()));
        return ApiResponse.ok(PaymentWebMapper.toResponse(result.transfer(), List.of(result.allocation())));
    }

    @PostMapping("/{transferId}/credit-refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Refund part of the transfer's unallocated money to the customer (a PENDING refund)")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CreditRefundResponse> refundCredit(@PathVariable UUID transferId,
                                                          @Valid @RequestBody CreditRefundRequest request,
                                                          @AuthenticatedUser CurrentUser user) {
        var result = ledger.refundCredit(new MoneyLedgerService.CreditRefundCommand(transferId, request.amount(),
                request.reason(), user.userId()));
        return ApiResponse.ok(new CreditRefundResponse(result.refundId(),
                PaymentWebMapper.toResponse(result.transfer(), null)));
    }
}

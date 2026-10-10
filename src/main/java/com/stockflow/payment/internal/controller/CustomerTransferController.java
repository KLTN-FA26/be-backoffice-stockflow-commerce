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
import com.stockflow.payment.internal.controller.dto.CustomerTransferResponse;
import com.stockflow.payment.internal.controller.dto.RecordTransferRequest;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.service.ReceivableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Transfers customers send to pay what they owe, recorded by the accountant from the bank statement
 * (SCRUM-431, kltn-docs 15 §4.3 step 3, BR-04, BR-07, BR-09).
 */
@RestController
@RequestMapping("/api/v1/customer-transfers")
@Tag(name = "Receivables")
@PermissionResource(code = PaymentResources.CUSTOMER_TRANSFERS, group = "Payment", label = "Customer transfers",
        route = "/payment/customer-transfers", apiPath = "/api/v1/customer-transfers",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE})
class CustomerTransferController {

    private final ReceivableService receivables;

    CustomerTransferController(ReceivableService receivables) {
        this.receivables = receivables;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a transfer from the bank statement; it pays the named receivables, or the "
            + "earliest due first, and the rest stays as the customer's credit")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<CustomerTransferResponse> record(@Valid @RequestBody RecordTransferRequest request,
                                                        @AuthenticatedUser CurrentUser user) {
        var result = receivables.recordTransfer(new ReceivableService.RecordTransferCommand(request.customerId(),
                request.reference(), request.amount(), request.currency(), request.receivedOn(), request.allocateTo(),
                request.note(), user.userId()));
        return ApiResponse.ok(toResponse(result.transfer(), result.allocations()));
    }

    @GetMapping
    @Operation(summary = "Recorded transfers, newest first")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CustomerTransferResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(receivables.transfers(customerId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                .map(t -> toResponse(t, null)));
    }

    private static CustomerTransferResponse toResponse(CustomerTransfer t, List<ReceivableBook.Allocation> allocations) {
        return new CustomerTransferResponse(t.id(), t.customerId(), t.reference(), t.amount(), t.unallocatedAmount(),
                t.currency(), t.receivedOn(), t.recordedBy(), t.recordedAt(), t.note(),
                allocations == null ? null : allocations.stream().map(ReceivableController::toResponse).toList());
    }
}

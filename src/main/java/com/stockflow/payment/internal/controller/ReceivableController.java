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
import com.stockflow.payment.internal.controller.dto.CarrierRemittanceRequest;
import com.stockflow.payment.internal.controller.dto.CustomerTransferResponse;
import com.stockflow.payment.internal.controller.dto.ReceivableKind;
import com.stockflow.payment.internal.controller.dto.ReceivableResponse;
import com.stockflow.payment.internal.domain.Collector;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import com.stockflow.payment.internal.service.MoneyLedgerService;
import com.stockflow.payment.internal.service.ReceivableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What customers owe on delivered credit orders (SCRUM-431, kltn-docs 15 §4.3, §5.3): the accountant's
 * and Sales' list, and a customer's own (18 §2: "xem công nợ"). G2-3: also the balances carriers collected on
 * delivery ({@code collector=CARRIER}) - never in the customer's own list - and the carrier's remittance that pays one.
 */
@RestController
@RequestMapping("/api/v1/receivables")
@Tag(name = "Receivables")
@PermissionResource(code = PaymentResources.RECEIVABLES, group = "Payment", label = "Receivables",
        route = "/payment/receivables", apiPath = "/api/v1/receivables",
        actions = {Action.VIEW_PAGE, Action.READ, Action.EXPORT})
class ReceivableController {

    private final ReceivableService receivables;
    private final MoneyLedgerService ledger;
    private final CustomerService customers;

    ReceivableController(ReceivableService receivables, MoneyLedgerService ledger, CustomerService customers) {
        this.receivables = receivables;
        this.ledger = ledger;
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "Receivables, earliest due first; filter by customer, order, status, due date, collector "
            + "and kind")
    @RequiresPermission(resource = PaymentResources.RECEIVABLES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<ReceivableResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) ReceivableStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
            @RequestParam(required = false) Collector collector,
            @RequestParam(required = false) ReceivableKind kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser CurrentUser user) {
        // Customers hold payment-receivables:READ for /me; this list is every customer's.
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(receivables.receivables(customerId, orderId, status, dueBefore, collector,
                kind == null ? null : kind.name(), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                .map(r -> PaymentWebMapper.toResponse(r, null)));
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
        // G2-3: what the carrier collected on delivery the customer already paid; it is the carrier's to remit.
        return ApiResponse.ok(receivables.receivables(customerId, status, null, Collector.CUSTOMER, null, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, null).map(r -> PaymentWebMapper.toResponse(r, null)));
    }

    @GetMapping("/{receivableId}")
    @Operation(summary = "One receivable, with the transfers that paid it")
    @RequiresPermission(resource = PaymentResources.RECEIVABLES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ReceivableResponse> one(@PathVariable UUID receivableId,
                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Receivable receivable = receivables.receivable(receivableId);
        return ApiResponse.ok(PaymentWebMapper.toResponse(receivable, receivables.allocationsOf(receivableId)));
    }

    /**
     * What a carrier remitted for the balance it collected on this order's delivery (kltn-docs 09 §4.3 step 4,
     * 15 §4.5): the accountant records it from the bank statement (15 BR-04); it pays exactly this receivable. Short,
     * the receivable stays part paid - the carrier's debt; over, the rest is kept as the customer's credit.
     */
    @PostMapping("/{receivableId}/carrier-remittances")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a carrier's remittance for a balance it collected on delivery")
    @RequiresPermission(resource = PaymentResources.CUSTOMER_TRANSFERS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<CustomerTransferResponse> carrierRemittance(@PathVariable UUID receivableId,
                                                                   @Valid @RequestBody CarrierRemittanceRequest request,
                                                                   @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var result = ledger.recordCarrierRemittance(new MoneyLedgerService.CarrierRemittanceCommand(receivableId,
                request.carrierName(), request.remittanceReference(), request.amount(), request.receivedOn(),
                request.note(), user.userId()));
        return ApiResponse.ok(PaymentWebMapper.toResponse(result.transfer(),
                result.allocation() == null ? List.of() : List.of(result.allocation())));
    }
}

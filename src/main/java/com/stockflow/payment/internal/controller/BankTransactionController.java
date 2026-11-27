package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.FieldError;
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
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.internal.controller.dto.AssignBankTransactionRequest;
import com.stockflow.payment.internal.controller.dto.BankTransactionResponse;
import com.stockflow.payment.internal.controller.dto.IgnoreBankTransactionRequest;
import com.stockflow.payment.internal.domain.BankTransaction;
import com.stockflow.payment.internal.domain.BankTransactionLog;
import com.stockflow.payment.internal.domain.BankTransactionStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.BankTransactionService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The transfers the bank feed reported and the Accountant's queue of them (credit plan 2026-11-26 part C; kltn-docs 15
 * BR-02, §4.2): a transfer that did not match one installment by itself waits here, and the Accountant assigns it to
 * installments - which records the receipt from it, under its bank reference - or ignores it with a reason.
 *
 * <p>{@code payment-bank-transactions} is the Accountant's (and the System Admin's): READ for the list and a transfer,
 * UPDATE for assigning and ignoring.</p>
 */
@RestController
@RequestMapping("/api/v1/bank-transactions")
@Tag(name = "Bank transfers")
@PermissionResource(code = PaymentResources.BANK_TRANSACTIONS, group = "Payment", label = "Bank transfers",
        route = "/payment/bank-transactions", apiPath = "/api/v1/bank-transactions",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class BankTransactionController {

    private final BankTransactionService transactions;
    private final CustomerService customers;
    private final IdentityService identities;

    BankTransactionController(BankTransactionService transactions, CustomerService customers,
                              IdentityService identities) {
        this.transactions = transactions;
        this.customers = customers;
        this.identities = identities;
    }

    @GetMapping
    @Operation(summary = "Bank transfers, newest first; status NEEDS_REVIEW is the Accountant's queue",
            description = "status: one or more of RECEIVED, APPLIED, NEEDS_REVIEW, IGNORED, comma-separated; every "
                    + "status when omitted. from / to bound when the bank booked the transfer (ISO instants, to "
                    + "exclusive). amount keeps the transfers of exactly that many dong.")
    @RequiresPermission(resource = PaymentResources.BANK_TRANSACTIONS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<BankTransactionResponse>> list(
            @Parameter(description = "Comma-separated statuses") @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Exact amount transferred, in dong (VND); every amount when omitted")
            @RequestParam(required = false) java.math.BigDecimal amount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(size == null ? Pages.DEFAULT_PAGE_SIZE : Math.max(size, 1), Pages.MAX_PAGE_SIZE);
        BankTransactionLog.Page found = transactions.list(statuses(status), from, to, amount, pageNumber,
                pageSize);
        return ApiResponse.ok(PageResponse.of(responses(found.rows()), pageNumber, pageSize, found.total()));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "One bank transfer")
    @RequiresPermission(resource = PaymentResources.BANK_TRANSACTIONS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<BankTransactionResponse> one(@PathVariable UUID transactionId,
                                                    @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(responses(List.of(transactions.get(transactionId).transaction())).getFirst());
    }

    @PostMapping("/{transactionId}/assignment")
    @Operation(summary = "Assign a transfer waiting for review to installments of one customer: records the receipt",
            description = "409 BANK_TRANSACTION_NOT_OPEN unless NEEDS_REVIEW; 409 BANK_TRANSACTION_OVER_ASSIGNED when "
                    + "the lines add up to more than the transfer; every refusal of recording a receipt otherwise "
                    + "(PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING, INSTALLMENT_NOT_PAYABLE, ...).")
    @RequiresPermission(resource = PaymentResources.BANK_TRANSACTIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<BankTransactionResponse> assign(@PathVariable UUID transactionId,
                                                       @Valid @RequestBody AssignBankTransactionRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var view = transactions.assign(transactionId, new BankTransactionService.AssignCommand(request.customerId(),
                request.lines().stream().map(line -> new PaymentReceipt.Payment(line.installmentId(), line.amount()))
                        .toList(), request.note()), user);
        return ApiResponse.ok(responses(List.of(view.transaction())).getFirst());
    }

    @PostMapping("/{transactionId}/ignore")
    @Operation(summary = "Ignore a transfer waiting for review, with the reason",
            description = "409 BANK_TRANSACTION_NOT_OPEN unless NEEDS_REVIEW.")
    @RequiresPermission(resource = PaymentResources.BANK_TRANSACTIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<BankTransactionResponse> ignore(@PathVariable UUID transactionId,
                                                       @Valid @RequestBody IgnoreBankTransactionRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var view = transactions.ignore(transactionId, request.reason(), user);
        return ApiResponse.ok(responses(List.of(view.transaction())).getFirst());
    }

    private static Set<BankTransactionStatus> statuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<BankTransactionStatus> statuses = EnumSet.noneOf(BankTransactionStatus.class);
        for (String one : raw.split(",")) {
            try {
                statuses.add(BankTransactionStatus.valueOf(one.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown bank transfer status",
                        List.of(FieldError.of("status", "one or more of "
                                + Arrays.toString(BankTransactionStatus.values()))));
            }
        }
        return statuses;
    }

    /** Rows with their customers' labels and reviewers' names, one read each for the whole page. */
    private List<BankTransactionResponse> responses(List<BankTransaction> found) {
        Map<UUID, CustomerLabel> labels = customers.labels(found.stream().map(BankTransaction::customerId)
                .filter(Objects::nonNull).distinct().toList());
        Map<UUID, String> names = identities.displayNames(found.stream().map(BankTransaction::reviewedBy)
                .filter(Objects::nonNull).distinct().toList());
        List<BankTransactionResponse> rows = new ArrayList<>();
        for (BankTransaction t : found) {
            CustomerLabel label = t.customerId() == null ? null : labels.get(t.customerId());
            rows.add(new BankTransactionResponse(t.id(), t.provider().name(), t.source().name(),
                    t.transfer().providerTransactionId(), t.transfer().accountNumber(), t.amount(), "VND",
                    t.transfer().content(), t.transfer().bankReference(), t.transfer().paymentCode(),
                    t.transfer().transactionAt(), t.receivedAt(), t.status().name(),
                    t.reviewReason() == null ? null : t.reviewReason().name(), t.reviewNote(), t.customerId(),
                    label == null ? null : label.code(), label == null ? null : label.name(), t.installmentId(),
                    t.receiptReference(), t.receiptId(),
                    t.status() == BankTransactionStatus.APPLIED && t.reviewedBy() == null, t.decidedAt(),
                    t.reviewedBy(), t.reviewedBy() == null ? null : names.get(t.reviewedBy()), t.reviewedAt(),
                    t.ignoreReason()));
        }
        return rows;
    }
}

package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.export.ExportResponses;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerSearch;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.payment.api.LoadingLoadLookup;
import com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse;
import com.stockflow.payment.internal.controller.dto.PaymentReceiptSummaryResponse;
import com.stockflow.payment.internal.controller.dto.RecordReceiptRequest;
import com.stockflow.payment.internal.controller.dto.VoidReceiptRequest;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.ReceiptFilter;
import com.stockflow.payment.internal.service.PaymentReceiptExportService;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Payment receipts (4B-6, kltn-docs 15 §4.2, §4.4, §5.3): the accountant records what the bank statement shows against
 * the installments it pays (BR-02, BR-05), once per statement line (BR-03), and voids one recorded wrong - never edits
 * it (BR-04).
 *
 * <p>The list filters by the day the money was received, and its total and its Excel file take the same filter
 * (2026-11-14 plan, BE2-A): the file is what the accountant imports into the accounting software, which keeps the books
 * of money this system does not (15 BR-06). Exporting is {@code payment-receipts:EXPORT}, the accountant's.</p>
 */
@RestController
@RequestMapping("/api/v1/payment-receipts")
@Tag(name = "Payment receipts")
@PermissionResource(code = PaymentResources.RECEIPTS, group = "Payment", label = "Payment receipts",
        route = "/payment/receipts", apiPath = "/api/v1/payment-receipts",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.EXPORT})
class PaymentReceiptController {

    private static final String RECEIVED_FROM = "First day the money was received, inclusive, ISO date";
    private static final String RECEIVED_TO = "Last day the money was received, inclusive, ISO date";
    private static final String SEARCH = "A part of the customer's name or tax code (case and accents aside), or of the "
            + "contract number or the order number of an installment a line paid (case aside); reference stays the "
            + "exact statement reference. 400 VALIDATION_FAILED when it matches more than 1,000 customers";

    private final PaymentReceiptService receipts;
    private final PaymentReceiptExportService exports;
    private final CustomerService customers;
    private final LoadingLoadLookup loadingLoads;
    private final CustomerSearch customerSearch;
    private final com.stockflow.identity.api.IdentityService identities;

    PaymentReceiptController(PaymentReceiptService receipts, PaymentReceiptExportService exports,
                             CustomerService customers, CustomerSearch customerSearch,
                             com.stockflow.identity.api.IdentityService identities,
                             LoadingLoadLookup loadingLoads) {
        this.identities = identities;
        this.customerSearch = customerSearch;
        this.receipts = receipts;
        this.exports = exports;
        this.customers = customers;
        this.loadingLoads = loadingLoads;
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
        return ApiResponse.ok(toResponse(detail));
    }

    @GetMapping
    @Operation(summary = "Payment receipts, latest first; filter by customer, status, statement reference, a search "
            + "box and the days the money was received; each line names the installment it paid",
            description = "receivedFrom / receivedTo are ISO dates, both inclusive, on the day received (received_on); "
                    + "400 VALIDATION_FAILED when receivedFrom is after receivedTo.")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<PaymentReceiptResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) PaymentReceiptStatus status,
            @RequestParam(required = false) String reference,
            @Parameter(description = SEARCH) @RequestParam(required = false) String search,
            @Parameter(description = RECEIVED_FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedFrom,
            @Parameter(description = RECEIVED_TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var found = receipts.receipts(filter(customerId, status, reference, search, receivedFrom, receivedTo),
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort);
        // One query each for the names and the installments of the whole page - not one per row.
        var labels = customers.labels(found.items().stream().map(r -> r.customerId()).toList());
        var names = namesOf(found.items());
        var paid = receipts.installmentsOf(found.items());
        return ApiResponse.ok(found.map(r -> PaymentWebMapper.toResponse(r, paid, labels, names, null, null)));
    }

    @GetMapping("/summary")
    @Operation(summary = "How many RECORDED receipts the list's filter matches and what they add up to",
            description = "Same filters as the list. Voided receipts count no money and are left out, even with "
                    + "status=VOIDED (then the count is 0).")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PaymentReceiptSummaryResponse> summary(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) PaymentReceiptStatus status,
            @RequestParam(required = false) String reference,
            @Parameter(description = SEARCH) @RequestParam(required = false) String search,
            @Parameter(description = RECEIVED_FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedFrom,
            @Parameter(description = RECEIVED_TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedTo,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var summary = exports.summary(filter(customerId, status, reference, search, receivedFrom, receivedTo));
        return ApiResponse.ok(new PaymentReceiptSummaryResponse(summary.count(), summary.totalAmount(),
                summary.currency(), summary.byCurrency().stream().map(total ->
                        new PaymentReceiptSummaryResponse.CurrencyTotal(total.currency(), total.count(),
                                total.amount())).toList()));
    }

    /**
     * The receipts of the list's filter as an Excel file, for the accounting software (15 BR-06): a sheet of receipts
     * and a sheet of their lines. Audited as an EXPORT with its filter.
     */
    @GetMapping("/export")
    @Operation(summary = "The receipts of the list's filter as XLSX: receipts, and the installments each line paid",
            description = "Needs payment-receipts:EXPORT. Same filters as the list; every receipt, not a page. 422 "
                    + "EXPORT_TOO_LARGE past 50,000 receipts or lines: narrow the filter.")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.EXPORT, scope = DataScope.ALL)
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) PaymentReceiptStatus status,
            @RequestParam(required = false) String reference,
            @Parameter(description = SEARCH) @RequestParam(required = false) String search,
            @Parameter(description = RECEIVED_FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedFrom,
            @Parameter(description = RECEIVED_TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedTo,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ExportResponses.xlsx(exports.export(filter(customerId, status, reference, search, receivedFrom,
                receivedTo)));
    }

    @GetMapping("/{receiptId}")
    @Operation(summary = "One payment receipt, with the installments it pays")
    @RequiresPermission(resource = PaymentResources.RECEIPTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PaymentReceiptResponse> one(@PathVariable UUID receiptId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var detail = receipts.receipt(receiptId);
        return ApiResponse.ok(toResponse(detail));
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
        return ApiResponse.ok(toResponse(detail));
    }

    /** The list's filter, the search box's customers read once ({@link CustomerSearchTerms}). */
    private ReceiptFilter filter(UUID customerId, PaymentReceiptStatus status, String reference, String search,
                                 LocalDate receivedFrom, LocalDate receivedTo) {
        return new ReceiptFilter(customerId, status, reference, receivedFrom, receivedTo, search,
                CustomerSearchTerms.idsMatching(customerSearch, search));
    }

    private java.util.Map<UUID, com.stockflow.customer.api.CustomerLabel> labelOf(PaymentReceipt receipt) {
        return customers.labels(java.util.List.of(receipt.customerId()));
    }

    /** One receipt with its customer and the names of who recorded and voided it. */
    private PaymentReceiptResponse toResponse(PaymentReceiptService.ReceiptDetail detail) {
        var beforeDeliveryOrders = detail.installments().values().stream()
                .filter(i -> i.milestone() == com.stockflow.payment.internal.domain.InstallmentMilestone.BEFORE_DELIVERY)
                .map(com.stockflow.payment.internal.domain.Installment::orderId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return PaymentWebMapper.toResponse(detail.receipt(), detail.installments(), labelOf(detail.receipt()),
                namesOf(java.util.List.of(detail.receipt())), detail.voidable(), detail.voidBlockedBy(),
                loadingLoads.loadingLoadCodes(beforeDeliveryOrders));
    }

    /** R5-8: who recorded and voided each receipt, by name, in one read for the whole page. */
    private java.util.Map<UUID, String> namesOf(java.util.List<PaymentReceipt> found) {
        var ids = found.stream().flatMap(r -> java.util.stream.Stream.of(r.recordedBy(), r.voidedBy()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? java.util.Map.of() : identities.displayNames(ids);
    }
}

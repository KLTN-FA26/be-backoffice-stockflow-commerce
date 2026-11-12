package com.stockflow.invoicing.internal.controller;

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
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.invoicing.internal.controller.dto.DrawUpEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceFileLinkResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceListItemResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceRegisterEntryResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceVersionRequest;
import com.stockflow.invoicing.internal.controller.dto.ProviderCallResponse;
import com.stockflow.invoicing.internal.controller.dto.ReviseEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.VoidEInvoiceRequest;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.service.EInvoiceDrafts;
import com.stockflow.invoicing.internal.service.EInvoiceIssuance;
import com.stockflow.invoicing.internal.service.EInvoiceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * E-invoices (WF-05 E2; plan §1.4; kltn-docs 21): the accountant reads the drafts drawn up on delivery, corrects the
 * buyer, issues them through the provider and follows the result; the Sales Manager reads. Back office only: every
 * handler refuses a portal account, which holds {@code einvoices:READ} for its own invoices and reads them through
 * {@link CustomerEInvoiceController}.
 *
 * <p><b>There is no endpoint that cancels or changes an issued invoice</b>, on purpose: the law has no cancellation
 * (TT 91/2026/TT-BTC Điều 10). A draft is voided; an issued invoice is adjusted or replaced by another invoice (package
 * E4).</p>
 *
 * <p>Handlers are public so the permission proxy advises them.</p>
 */
@RestController
@RequestMapping("/api/v1/einvoices")
@Tag(name = "E-invoices")
@PermissionResource(code = InvoicingResources.EINVOICES, group = "Invoicing", label = "E-invoices",
        route = "/invoicing/einvoices", apiPath = "/api/v1/einvoices",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE, Action.EXPORT})
class EInvoiceController {

    private final EInvoiceDrafts drafts;
    private final EInvoiceIssuance issuance;
    private final EInvoiceQueryService queries;
    private final Clock clock;

    EInvoiceController(EInvoiceDrafts drafts, EInvoiceIssuance issuance, EInvoiceQueryService queries, Clock clock) {
        this.drafts = drafts;
        this.issuance = issuance;
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "E-invoices: those still to issue first, nearest deadline first; filter by status, type, "
            + "order, customer, invoice date and deadline (dueBefore: not yet issued and due before that instant)")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<EInvoiceListItemResponse>> list(
            @RequestParam(required = false) EInvoiceStatus status,
            @RequestParam(required = false) EInvoiceType type,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dueBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Instant now = clock.instant();
        return ApiResponse.ok(queries.list(new EInvoiceQueries.Filter(status, type, orderId, customerId, issuedFrom,
                        issuedTo, dueBefore), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(row -> InvoicingWebMapper.toResponse(row, now)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One e-invoice with its lines, totals by VAT rate and total in words")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> get(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(queries.get(id), clock.instant()));
    }

    @GetMapping("/{id}/provider-calls")
    @Operation(summary = "The calls made to the provider for this e-invoice, oldest first")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<ProviderCallResponse>> providerCalls(@PathVariable UUID id,
                                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(queries.calls(id).stream().map(InvoicingWebMapper::toResponse).toList());
    }

    @GetMapping("/{id}/files/{kind}")
    @Operation(summary = "A short-lived link to the issued e-invoice's XML or PDF (kind: XML or PDF); 409 "
            + "EINVOICE_NOT_ISSUED before it is issued")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<EInvoiceFileLinkResponse> file(@PathVariable UUID id,
                                                      @PathVariable EInvoiceQueryService.FileKind kind,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        DownloadLink link = queries.fileLink(id, kind);
        return ApiResponse.ok(new EInvoiceFileLinkResponse(link.url(), link.expiresAt()));
    }

    @PostMapping
    @Operation(summary = "Draw the draft e-invoice of a delivered shipment up, or return the one in force: for a "
            + "shipment whose draft was voided or never made. 409 EINVOICE_NOT_INVOICEABLE unless it is delivered")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> drawUp(@Valid @RequestBody DrawUpEInvoiceRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(
                drafts.drawUp(request.orderId(), request.shipmentId()).invoice(), clock.instant()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct a draft: the buyer as the invoice names it, and names of lines. Quantities and "
            + "prices are the contract's and do not change. 409 EINVOICE_NOT_DRAFT otherwise")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> revise(@PathVariable UUID id, @Valid @RequestBody ReviseEInvoiceRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(drafts.revise(new EInvoiceDrafts.Revise(id,
                request.version(), request.buyerName(), request.buyerTaxCode(), request.buyerAddress(),
                request.buyerEmail(), request.lineNames())), clock.instant()));
    }

    @PostMapping("/{id}/buyer-refresh")
    @Operation(summary = "Copy the buyer onto the draft again from the customer's legal profile")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> refreshBuyer(@PathVariable UUID id,
                                                      @Valid @RequestBody EInvoiceVersionRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(drafts.refreshBuyer(id, request.version()),
                clock.instant()));
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Void a draft that was never sent, with a reason. Never an issued invoice: that is adjusted "
            + "or replaced, not voided")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> voidDraft(@PathVariable UUID id,
                                                   @Valid @RequestBody VoidEInvoiceRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(drafts.voidDraft(id, request.version(), request.reason(),
                user.userId()), clock.instant()));
    }

    @PostMapping("/{id}/reopening")
    @Operation(summary = "Take a rejected e-invoice back to a draft to correct it")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> reopen(@PathVariable UUID id,
                                                @Valid @RequestBody EInvoiceVersionRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(drafts.reopen(id, request.version()), clock.instant()));
    }

    @PostMapping("/{id}/issuance")
    @Operation(summary = "Issue a draft through the provider. Answers SUBMITTING while the provider works on it, "
            + "ISSUED or REJECTED once it has answered. 409 EINVOICE_BUYER_PROFILE_INCOMPLETE while the buyer's name, "
            + "tax code, address or invoice e-mail is missing")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> issue(@PathVariable UUID id,
                                               @Valid @RequestBody EInvoiceVersionRequest request,
                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(issuance.issue(id, request.version(), user.userId()),
                clock.instant()));
    }

    @PostMapping("/{id}/reconciliation")
    @Operation(summary = "Ask the provider what became of an e-invoice that is SUBMITTING, by its transaction id; it is "
            + "sent only if the provider never received it. Changes nothing in any other status")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> reconcile(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(issuance.reconcile(id), clock.instant()));
    }

    @GetMapping("/register")
    @Operation(summary = "The register of a period: every e-invoice issued with a date from..to, by symbol and number")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<EInvoiceRegisterEntryResponse>> register(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(queries.register(from, to).stream().map(InvoicingWebMapper::toResponse).toList());
    }

    @GetMapping("/register/export")
    @Operation(summary = "The register of a period as CSV (UTF-8 with a byte-order mark)")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.EXPORT, scope = DataScope.ALL)
    public ResponseEntity<byte[]> exportRegister(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("einvoice-register-%s-%s.csv".formatted(from, to)).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(queries.registerCsv(from, to).getBytes(StandardCharsets.UTF_8));
    }
}

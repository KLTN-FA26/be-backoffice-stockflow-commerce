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
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.invoicing.internal.controller.dto.AdjustEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.CorrectionBuyerRequest;
import com.stockflow.invoicing.internal.controller.dto.CorrectionLineRequest;
import com.stockflow.invoicing.internal.controller.dto.DrawUpEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceErrorNoticeResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceFileLinkResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceListItemResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceRegisterEntryResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceVersionRequest;
import com.stockflow.invoicing.internal.controller.dto.ProviderCallResponse;
import com.stockflow.invoicing.internal.controller.dto.RecordErrorNoticeRequest;
import com.stockflow.invoicing.internal.controller.dto.ReplaceEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.ReviseEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.VoidEInvoiceRequest;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.service.EInvoiceCorrections;
import com.stockflow.invoicing.internal.service.EInvoiceDrafts;
import com.stockflow.invoicing.internal.service.EInvoiceErrorNotices;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
 * (TT 91/2026/TT-BTC Điều 10). A draft is voided. An issued invoice that was wrong is corrected in one of the ways the
 * law gives (WF-05 E4): a wrong buyer name or address with the right tax code is <b>notified</b> on Mẫu 04/SS-HĐĐT and
 * no invoice is drawn up ({@code /error-notices}); a wrong tax code, quantity, price, rate or amount is
 * <b>adjusted</b> or <b>replaced</b> by another invoice naming it ({@code /adjustments}, {@code /replacements}), which
 * waits for the written agreement with the buyer ({@code /agreement}) and is then issued like any draft. Drawing a
 * correction up is CREATE, attaching its agreement UPDATE, and issuing it or notifying the tax authority APPROVE: the
 * permission set has no action of its own for corrections.</p>
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
    private final EInvoiceCorrections corrections;
    private final EInvoiceErrorNotices errorNotices;
    private final Clock clock;

    EInvoiceController(EInvoiceDrafts drafts, EInvoiceIssuance issuance, EInvoiceQueryService queries,
                       EInvoiceCorrections corrections, EInvoiceErrorNotices errorNotices, Clock clock) {
        this.corrections = corrections;
        this.errorNotices = errorNotices;
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
    @Operation(summary = "Issue a draft through the provider. Answers SUBMITTING at once: the provider is called "
            + "afterwards, and the invoice becomes ISSUED or REJECTED when it has answered (read it again, or ask with "
            + "/reconciliation). 409 EINVOICE_BUYER_PROFILE_INCOMPLETE while the buyer's name, tax code, address or "
            + "invoice e-mail is missing")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> issue(@PathVariable UUID id,
                                               @Valid @RequestBody EInvoiceVersionRequest request,
                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(issuance.issue(id, request.version(), user.userId()),
                clock.instant()));
    }

    @PostMapping("/{id}/resubmission")
    @Operation(summary = "Send as a new transaction an e-invoice the provider does not know and no longer remembers "
            + "the transaction of (warning UNKNOWN_TO_PROVIDER). Done only when the provider, asked now, confirms it "
            + "holds no such invoice; otherwise 409 EINVOICE_RESUBMISSION_REFUSED. Answers SUBMITTING")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> resubmit(@PathVariable UUID id,
                                                  @Valid @RequestBody EInvoiceVersionRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(issuance.resubmitAsNew(id, request.version(),
                user.userId()), clock.instant()));
    }

    @PostMapping("/{id}/reconciliation")
    @Operation(summary = "Ask the provider what became of an e-invoice that is SUBMITTING, by its transaction id; it is "
            + "sent only if the provider never received it. Changes nothing in any other status")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> reconcile(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(issuance.reconcile(id), clock.instant()));
    }

    // ---- corrections of an issued invoice (WF-05 E4; TT 91/2026/TT-BTC Điều 10) -------------------------------------

    @GetMapping("/{id}/corrections")
    @Operation(summary = "The adjustments and the replacement of an e-invoice, voided ones too, oldest first")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<EInvoiceListItemResponse>> corrections(@PathVariable UUID id,
                                                                   @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Instant now = clock.instant();
        return ApiResponse.ok(queries.corrections(id).stream().map(row -> InvoicingWebMapper.toResponse(row, now))
                .toList());
    }

    @PostMapping("/{id}/adjustments")
    @Operation(summary = "Draw up a draft adjustment of an issued e-invoice: signed lines by the invoice's line "
            + "(below zero reduces). basis ERROR (wrong tax code, quantity, price, rate, amount; needs the agreement "
            + "before it is issued), RETURN (goods returned; names the return, only reduces) or TRADE_DISCOUNT (by "
            + "volume or revenue; only reduces, may list the buyer's other invoices). 409 "
            + "EINVOICE_CORRECTION_FORM_FIXED when the invoice's errors are corrected by replacement")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> adjust(@PathVariable UUID id, @Valid @RequestBody AdjustEInvoiceRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(corrections.adjust(new EInvoiceCorrections.Adjust(id,
                request.basis(), request.reason(), buyer(request.buyer()), lines(request.lines()),
                request.returnRequestId(), request.listedInvoiceIds())), clock.instant()));
    }

    @PostMapping("/{id}/replacements")
    @Operation(summary = "Draw up the draft replacement of an issued e-invoice: its lines as they were, with what is "
            + "corrected (a quantity of 0 drops a line). The original becomes REPLACED when the replacement is issued. "
            + "409 EINVOICE_CORRECTION_FORM_FIXED when the invoice has an adjustment or is already being replaced")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> replace(@PathVariable UUID id,
                                                 @Valid @RequestBody ReplaceEInvoiceRequest request,
                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(corrections.replace(new EInvoiceCorrections.Replace(id,
                request.reason(), buyer(request.buyer()), lines(request.lines()))), clock.instant()));
    }

    @PutMapping(path = "/{id}/agreement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach the written agreement with the buyer to a draft correction of an error (multipart: "
            + "file - the signed PDF, number, date, version). Without it the correction is not issued: 409 "
            + "EINVOICE_AGREEMENT_REQUIRED")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> attachAgreement(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam String number,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam long version,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "agreement.pdf"
                : file.getOriginalFilename();
        try (InputStream content = file.getInputStream()) {
            return ApiResponse.ok(InvoicingWebMapper.toResponse(corrections.attachAgreement(id, version, number, date,
                    new FileUpload(name, file.getContentType(), file.getSize(), content)), clock.instant()));
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the multipart upload", unreadable);
        }
    }

    @GetMapping("/{id}/agreement")
    @Operation(summary = "A short-lived link to the agreement attached to a correction")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<EInvoiceFileLinkResponse> agreement(@PathVariable UUID id,
                                                           @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        DownloadLink link = queries.agreementLink(id);
        return ApiResponse.ok(new EInvoiceFileLinkResponse(link.url(), link.expiresAt()));
    }

    @GetMapping("/{id}/error-notices")
    @Operation(summary = "The 04/SS-HDDT notices of an e-invoice (invoice drawn up wrongly), oldest first, each with "
            + "the tax authority's answer")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<EInvoiceErrorNoticeResponse>> errorNotices(@PathVariable UUID id,
                                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        queries.get(id);
        return ApiResponse.ok(errorNotices.of(id).stream().map(InvoicingWebMapper::toResponse).toList());
    }

    @PostMapping("/{id}/error-notices")
    @Operation(summary = "Notify an issued e-invoice as drawn up wrongly (04/SS-HDDT): the buyer's name or address "
            + "was wrong and the tax code right. No new invoice is drawn up and the invoice does not change. A wrong "
            + "tax code or amount is adjusted or replaced instead")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceErrorNoticeResponse> notifyError(@PathVariable UUID id,
                                                                @Valid @RequestBody RecordErrorNoticeRequest request,
                                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(errorNotices.record(new EInvoiceErrorNotices.Record(id,
                request.subject(), request.reason(), request.correctBuyerName(), request.correctBuyerAddress()),
                user.userId())));
    }

    @PostMapping("/{id}/error-notices/{noticeId}/sending")
    @Operation(summary = "Send again a notice the provider never got, or ask for the answer of one on its way. 409 "
            + "EINVOICE_ERROR_NOTICE_NOT_SENDABLE once it was answered")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceErrorNoticeResponse> sendErrorNotice(@PathVariable UUID id, @PathVariable UUID noticeId,
                                                                    @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(errorNotices.send(id, noticeId)));
    }

    private static EInvoiceCorrections.Buyer buyer(CorrectionBuyerRequest buyer) {
        return buyer == null ? null
                : new EInvoiceCorrections.Buyer(buyer.name(), buyer.taxCode(), buyer.address(), buyer.email());
    }

    private static List<EInvoiceCorrections.LineChange> lines(List<CorrectionLineRequest> lines) {
        return lines == null ? List.of() : lines.stream().map(line -> new EInvoiceCorrections.LineChange(
                line.originalLineId(), line.quantity(), line.unitPrice(), line.taxRate(), line.itemName())).toList();
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

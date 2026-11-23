package com.stockflow.invoicing.internal.controller;

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
import com.stockflow.invoicing.internal.controller.dto.EInvoiceMailResendResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceRegisterEntryResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceSignatureResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceVersionRequest;
import com.stockflow.invoicing.internal.controller.dto.IssueEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.ProviderCallResponse;
import com.stockflow.invoicing.internal.controller.dto.RecordErrorNoticeRequest;
import com.stockflow.invoicing.internal.controller.dto.ReplaceEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.ReviseEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.VoidEInvoiceRequest;
import com.stockflow.invoicing.internal.controller.dto.WithdrawEInvoiceRequest;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.service.EInvoiceCorrections;
import com.stockflow.invoicing.internal.service.EInvoiceDrafts;
import com.stockflow.invoicing.internal.service.EInvoiceErrorNotices;
import com.stockflow.invoicing.internal.service.EInvoiceIssuance;
import com.stockflow.invoicing.internal.service.EInvoiceMails;
import com.stockflow.invoicing.internal.service.EInvoiceQueryService;
import com.stockflow.invoicing.internal.service.EInvoiceSignatures;
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
 * <p>One handler is not the accountant's: {@code /email} sends an issued invoice to its buyer again, for whoever records
 * the customer's invoice e-mail ({@code customer-legal-profiles:UPDATE}, Sales included; audit r2 M-1).</p>
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
    private final EInvoiceSignatures signatures;
    private final EInvoiceMails mails;
    private final Clock clock;

    EInvoiceController(EInvoiceDrafts drafts, EInvoiceIssuance issuance, EInvoiceQueryService queries,
                       EInvoiceCorrections corrections, EInvoiceErrorNotices errorNotices,
                       EInvoiceSignatures signatures, EInvoiceMails mails, Clock clock) {
        this.signatures = signatures;
        this.mails = mails;
        this.corrections = corrections;
        this.errorNotices = errorNotices;
        this.drafts = drafts;
        this.issuance = issuance;
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "E-invoices: those still to issue first, nearest deadline first; filter by status, type, "
            + "order, customer, invoice date and deadline (dueBefore: not yet issued and due before that instant), and "
            + "warning (invoices carrying that warning, e.g. ISSUED_LATE: issued after the day of their delivery)")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<EInvoiceListItemResponse>> list(
            @RequestParam(required = false) EInvoiceStatus status,
            @RequestParam(required = false) EInvoiceType type,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dueBefore,
            @RequestParam(required = false) com.stockflow.invoicing.internal.domain.EInvoice.Warning warning,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Instant now = clock.instant();
        return ApiResponse.ok(queries.list(new EInvoiceQueries.Filter(status, type, orderId, customerId, issuedFrom,
                        issuedTo, dueBefore, warning), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(row -> InvoicingWebMapper.toResponse(row, now)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One e-invoice with its lines, totals by VAT rate and total in words. A draft names an "
            + "invoice dated before it and still to be issued under the series it would be sent under "
            + "(earlierPendingInvoice, warning EARLIER_INVOICE_PENDING): a warning - only one of them at the provider "
            + "keeps the draft back")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> get(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        com.stockflow.invoicing.internal.domain.EInvoice.Snapshot invoice = queries.get(id);
        return ApiResponse.ok(response(invoice, clock.instant(),
                queries.earlierPending(invoice).orElse(null)));
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

    @GetMapping("/{id}/signature")
    @Operation(summary = "The seller's digital signature on the issued e-invoice's XML, checked now: who signed, when, "
            + "the certificate's issuer, and whether the content is still what was signed (status VALID, "
            + "CONTENT_CHANGED, SIGNATURE_INVALID, NOT_SIGNED). 409 EINVOICE_NOT_ISSUED before it is issued")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<EInvoiceSignatureResponse> signature(@PathVariable UUID id,
                                                            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(signatures.ofInvoice(id)));
    }

    @PostMapping(path = "/signature-checks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Check the digital signature of any e-invoice XML handed in (multipart: file), e.g. a "
            + "buyer's copy or one changed on purpose: the same answer as /{id}/signature. Nothing is stored. "
            + "413 PAYLOAD_TOO_LARGE above 5 MB")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<EInvoiceSignatureResponse> checkSignature(@RequestPart("file") MultipartFile file,
                                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        try (InputStream content = file.getInputStream()) {
            return ApiResponse.ok(InvoicingWebMapper.toResponse(signatures.ofXml(content)));
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the multipart upload", unreadable);
        }
    }

    @PostMapping
    @Operation(summary = "Draw the draft e-invoice of a delivered shipment up, or return the one in force: for a "
            + "shipment whose draft was voided or never made. 409 EINVOICE_NOT_INVOICEABLE unless it is delivered. "
            + "With no shipmentId: the invoice of the rest of the order's shipping fee after its undelivered rest was "
            + "closed; 409 EINVOICE_NOT_INVOICEABLE when no part of the fee is left to it")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> drawUp(@Valid @RequestBody DrawUpEInvoiceRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        if (request.shipmentId() == null) {
            EInvoiceDrafts.DrawnUp rest = drafts.drawUpShippingFeeRest(request.orderId(), clock.instant())
                    .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE,
                            "No part of the shipping fee of order %s is left to an invoice of its own: it has none, "
                                    .formatted(request.orderId())
                                    + "its invoices carry all of it, or a delivery still to be invoiced will"));
            return ApiResponse.ok(response(rest.invoice(), clock.instant()));
        }
        return ApiResponse.ok(response(
                drafts.drawUp(request.orderId(), request.shipmentId()).invoice(), clock.instant()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct a draft: the buyer as the invoice names it, and names of lines. Quantities and "
            + "prices are the contract's and do not change. 409 EINVOICE_NOT_DRAFT otherwise")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> revise(@PathVariable UUID id, @Valid @RequestBody ReviseEInvoiceRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(drafts.revise(new EInvoiceDrafts.Revise(id,
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
        return ApiResponse.ok(response(drafts.refreshBuyer(id, request.version()),
                clock.instant()));
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Void a draft that was never sent, with a reason. Never an issued invoice: that is adjusted "
            + "or replaced, not voided. The reduction of a failed delivery is voided only with reasonCode "
            + "UNSENDABLE_REVERSAL (einvoices:APPROVE), when no provider can send it - its original's provider is not "
            + "usable or issues no corrections, or refused it twice under one code - to be made on the provider's portal "
            + "and recorded (/external-reductions); 409 EINVOICE_REVERSAL_STILL_SENDABLE otherwise")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> voidDraft(@PathVariable UUID id,
                                                   @Valid @RequestBody VoidEInvoiceRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        if (request.reasonCode() != null) {
            // WF-05 BE-10 (Q-8): a reduction left unissued has legal weight - the decision is the approver's.
            user.require(InvoicingResources.EINVOICES, Action.APPROVE);
            return ApiResponse.ok(response(drafts.voidUnsendableReversal(id, request.version(), request.reason(),
                    user.userId()), clock.instant()));
        }
        return ApiResponse.ok(response(drafts.voidDraft(id, request.version(), request.reason(),
                user.userId()), clock.instant()));
    }

    @PostMapping("/{id}/reopening")
    @Operation(summary = "Take a rejected e-invoice back to a draft to correct it")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> reopen(@PathVariable UUID id,
                                                @Valid @RequestBody EInvoiceVersionRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(drafts.reopen(id, request.version()), clock.instant()));
    }

    @PostMapping("/{id}/issuance")
    @Operation(summary = "Issue a draft through the provider. Answers SUBMITTING at once: the provider is called "
            + "afterwards, and the invoice becomes ISSUED or REJECTED when it has answered (read it again, or ask with "
            + "/reconciliation). 409 EINVOICE_BUYER_PROFILE_INCOMPLETE while the buyer's name, tax code or address is "
            + "missing (a missing invoice e-mail is only the warning BUYER_EMAIL_MISSING); 409 "
            + "EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE for a correction whose original was issued through a provider "
            + "that is not usable any more (it goes only through that provider); 409 EINVOICE_OLDER_DRAFT_PENDING while an invoice of the same series dated before it is at the "
            + "provider (a draft or a rejected one does not keep it back; never under a provider that takes only "
            + "today's date, SePay); 409 EINVOICE_TAX_EXEMPT_NOT_SUPPORTED for a "
            + "line at 0% (tax class EXEMPT, which cannot be issued yet). Every check of the automatic issuing is made "
            + "again first: 409 EINVOICE_NOT_INVOICEABLE when its shipment is not delivered now; 409 "
            + "EINVOICE_DRAFT_OUT_OF_DATE (details.warnings) when a warning found now, or a buyer the customer's profile "
            + "names otherwise (BUYER_PROFILE_CHANGED), is not in acknowledgedWarnings; 400 "
            + "EINVOICE_LATE_ISSUE_REASON_REQUIRED for an original issued after its delivery day without lateReason "
            + "(it is dated today and labelled ISSUED_LATE); 409 EINVOICE_QUOTA_EXHAUSTED while the provider's plan has "
            + "no invoice left (it stays a draft)")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> issue(@PathVariable UUID id,
                                               @Valid @RequestBody IssueEInvoiceRequest request,
                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(issuance.issue(id, request.version(),
                request.acknowledgedWarnings() == null ? java.util.Set.of()
                        : java.util.Set.copyOf(request.acknowledgedWarnings()),
                request.lateReason(), user.userId()), clock.instant()));
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
        return ApiResponse.ok(response(issuance.resubmitAsNew(id, request.version(),
                user.userId()), clock.instant()));
    }

    @PostMapping("/{id}/withdrawal")
    @Operation(summary = "Take back an e-invoice stuck at the provider (SUBMITTING): it becomes REJECTED, to be "
            + "reopened and issued again under a new transaction, and the old transaction is asked about daily for 30 "
            + "days. Only when it was last sent longer ago than the provider can still be working on it "
            + "(stockflow.invoicing.withdraw-after, 3 hours), the provider never said it holds it, and, asked now, "
            + "either has no record of it (code WITHDRAWN_NO_PROVIDER_RECORD, the reason as its message) or says it "
            + "cannot confirm the result and nothing was sent for stockflow.invoicing.withdraw-unknown-after (1 day) - "
            + "then only with supportReference, the provider's support's written answer (code "
            + "WITHDRAWN_RESULT_UNCONFIRMED; 409 EINVOICE_WITHDRAWAL_EVIDENCE_REQUIRED without it). Otherwise 409 "
            + "EINVOICE_WITHDRAWAL_REFUSED with details.withdrawableFrom (absent when never) and details.providerState "
            + "(PENDING, UNKNOWN, NOT_FOUND, UNCONFIRMED, ISSUED, REJECTED; absent when not asked): an invoice the "
            + "provider holds or answers unclearly for is recorded from its portal (/portal-result). 503 "
            + "EINVOICE_PROVIDER_UNAVAILABLE when the provider does not answer")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> withdraw(@PathVariable UUID id,
                                                  @Valid @RequestBody WithdrawEInvoiceRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(issuance.withdraw(id, request.version(), request.reason(),
                request.supportReference(), user.userId()), clock.instant()));
    }

    @PostMapping("/{id}/reconciliation")
    @Operation(summary = "Ask the provider what became of an e-invoice that is SUBMITTING, by its transaction id; it is "
            + "sent only if the provider never received it. Changes nothing in any other status")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> reconcile(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(issuance.reconcile(id), clock.instant()));
    }

    @PostMapping(path = "/{id}/portal-result", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Record the result of an e-invoice stuck at the provider (SUBMITTING) as the provider's portal "
            + "shows it (WF-05 Q-8): outcome ISSUED with invoiceNumber, taxAuthorityCode, lookupCode and the files xml "
            + "and pdf - the XML must be this invoice's (422 EINVOICE_PORTAL_FILE_INVALID) and the provider, asked now, "
            + "must not say otherwise (409 EINVOICE_PORTAL_RECORD_MISMATCH); or REFUSED_BY_TAX_AUTHORITY (REJECTED, "
            + "the old transaction watched for 30 days). note is required. 409 EINVOICE_INVALID_TRANSITION unless "
            + "SUBMITTING")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> recordPortalResult(
            @PathVariable UUID id,
            @RequestParam EInvoiceIssuance.PortalOutcome outcome,
            @RequestParam(required = false) Integer invoiceNumber,
            @RequestParam(required = false) String taxAuthorityCode,
            @RequestParam(required = false) String lookupCode,
            @RequestPart(name = "xml", required = false) MultipartFile xml,
            @RequestPart(name = "pdf", required = false) MultipartFile pdf,
            @RequestParam String note,
            @RequestParam long version,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(issuance.recordPortalResult(id, version,
                new EInvoiceIssuance.PortalRecord(outcome, invoiceNumber, taxAuthorityCode, lookupCode, bytes(xml),
                        bytes(pdf), note), user.userId()), clock.instant()));
    }

    @PostMapping(path = "/{id}/external-reductions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Record a reduction of a failed delivery made on the provider's portal (WF-05 Q-8, Q-9): for an "
            + "original whose own reduction was voided as UNSENDABLE_REVERSAL, or a replacement whose shipment is not "
            + "delivered now (basis DELIVERY_FAILED, the default); or a return's goods on a replacement (basis RETURN "
            + "with returnRequestId: the refunded pieces kept for it). templateCode, symbol, invoiceNumber, issueDate, "
            + "the files xml and pdf, and a note; "
            + "taxAuthorityCode and lookupCode when the XML does not carry them. Recorded as an issued adjustment "
            + "(issuedVia PORTAL_RECORD) of what the invoice still invoices. 409 "
            + "EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE for any other invoice; 422 EINVOICE_PORTAL_FILE_INVALID for an "
            + "XML of another invoice")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> recordExternalReduction(
            @PathVariable UUID id,
            @RequestParam String templateCode,
            @RequestParam String symbol,
            @RequestParam Integer invoiceNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
            @RequestParam(required = false) String taxAuthorityCode,
            @RequestParam(required = false) String lookupCode,
            @RequestPart(name = "xml", required = false) MultipartFile xml,
            @RequestPart("pdf") MultipartFile pdf,
            @RequestParam String note,
            @RequestParam(required = false) com.stockflow.invoicing.internal.domain.CorrectionBasis basis,
            @RequestParam(required = false) UUID returnRequestId,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(issuance.recordExternalReduction(id, new EInvoiceIssuance.ExternalReduction(
                templateCode, symbol, invoiceNumber, issueDate, taxAuthorityCode, lookupCode, bytes(xml), bytes(pdf),
                note, basis, returnRequestId), user.userId()), clock.instant()));
    }

    @PostMapping("/{id}/email")
    @Operation(summary = "E-mail an issued e-invoice to its buyer again, with its PDF and XML, to the customer's invoice "
            + "e-mail as it is now (WF-05 audit r2 M-1; HĐ-8: an invoice issued while the customer had no e-mail, once "
            + "Sales has recorded one; or after a mail went to an address since corrected). Accepted at once, sent a "
            + "moment later and retried like the first mail; asked twice within a second, one mail. For whoever records "
            + "the customer's legal profile (customer-legal-profiles:UPDATE: Sales, Sales Manager, accountant). 409 "
            + "EINVOICE_NOT_ISSUED before it is issued; 409 EINVOICE_INVALID_TRANSITION (details.reason "
            + "SIGNATURE_INVALID) for an invoice whose signature does not hold; 409 EINVOICE_BUYER_PROFILE_INCOMPLETE "
            + "(details.missing [EMAIL]) while the customer has no invoice e-mail")
    @RequiresPermission(resource = InvoicingResources.LEGAL_PROFILES, action = Action.UPDATE,
            scope = DataScope.ALL)
    public ApiResponse<EInvoiceMailResendResponse> resendEmail(@PathVariable UUID id,
                                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        EInvoiceMails.Resend resend = mails.resend(id, user.userId());
        return ApiResponse.ok(new EInvoiceMailResendResponse(resend.einvoiceId(), resend.recipient(),
                resend.requestedAt()));
    }

    @PostMapping("/{id}/delivery-failure-reduction")
    @Operation(summary = "Reduce for the failed delivery (WF-05 BE-11): draw up the reduction of an issued invoice whose "
            + "shipment is not delivered now, as the system does when it hears of the failure - what the invoice and "
            + "its issued adjustments still invoice is taken off, draft corrections of it are voided, and it no longer "
            + "stands for its shipment. 409 EINVOICE_REVERSAL_NOT_APPLICABLE when it is not issued, is reversed "
            + "already, its shipment is delivered, or a correction of it is at the provider; 409 "
            + "EINVOICE_REPLACEMENT_NOT_ADJUSTABLE for a replacement (reduce it on the provider's portal)")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceResponse> reduceForFailedDelivery(@PathVariable UUID id,
                                                                 @Valid @RequestBody EInvoiceVersionRequest request,
                                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(response(drafts.reduceForFailedDelivery(id, request.version()), clock.instant()));
    }

    /** A part's bytes; null for a part not sent. The size is bounded by the multipart limit. */
    private static byte[] bytes(MultipartFile part) {
        if (part == null || part.isEmpty()) {
            return null;
        }
        try {
            return part.getBytes();
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the multipart upload", unreadable);
        }
    }

    /** The invoice as staff read it, with whether its provider sends an error notice (F-11). */
    private EInvoiceResponse response(com.stockflow.invoicing.internal.domain.EInvoice.Snapshot invoice, Instant now) {
        return response(invoice, now, null);
    }

    private EInvoiceResponse response(com.stockflow.invoicing.internal.domain.EInvoice.Snapshot invoice, Instant now,
                                      String earlierPending) {
        return InvoicingWebMapper.toResponse(invoice, now, earlierPending, queries.errorNoticeSupported(invoice),
                issuance.withdrawability(invoice), issuance.externalReductions(invoice),
                queries.watch(invoice).orElse(null));
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
        return ApiResponse.ok(response(corrections.adjust(new EInvoiceCorrections.Adjust(id,
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
        return ApiResponse.ok(response(corrections.replace(new EInvoiceCorrections.Replace(id,
                request.reason(), buyer(request.buyer()), lines(request.lines()))), clock.instant()));
    }

    @PutMapping(path = "/{id}/agreement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach the written agreement with the buyer to a draft correction of an error (multipart: "
            + "file - the signed PDF, number, date, version). Without it the correction is not issued: 409 "
            + "EINVOICE_AGREEMENT_REQUIRED. On a DELIVERY_FAILED reduction the same fields carry optional written "
            + "evidence of the failed delivery; it is issued without")
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
            return ApiResponse.ok(response(corrections.attachAgreement(id, version, number, date,
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
            + "tax code or amount is adjusted or replaced instead. 409 EINVOICE_ERROR_NOTICE_NOT_SUPPORTED when the "
            + "invoice's provider cannot send the form (SePay, Viettel): it is filed on the provider's or the tax "
            + "authority's portal, and nothing is recorded. The buyer is told once the provider took the notice in")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceErrorNoticeResponse> notifyError(@PathVariable UUID id,
                                                                @Valid @RequestBody RecordErrorNoticeRequest request,
                                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(errorNotices.record(new EInvoiceErrorNotices.Notify(id,
                request.subject(), request.reason(), request.correctBuyerName(), request.correctBuyerAddress()),
                user.userId())));
    }

    @PostMapping("/{id}/error-notices/{noticeId}/sending")
    @Operation(summary = "Send again a notice the provider never got, or ask for the answer of one on its way. 409 "
            + "EINVOICE_ERROR_NOTICE_NOT_SENDABLE once it was answered; 409 EINVOICE_ERROR_NOTICE_NOT_SUPPORTED when "
            + "the invoice's provider cannot send it")
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

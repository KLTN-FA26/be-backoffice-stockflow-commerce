package com.stockflow.invoicing.internal.controller;

import com.stockflow.common.document.PdfDownload;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.invoicing.internal.service.EInvoiceAgreementPaperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The agreement to adjust or replace an invoice, drafted as a PDF from the correction - the paper the two companies
 * sign; the signed scan is attached through {@code PUT /api/v1/einvoices/{id}/agreement}, which is another thing. Read
 * under the e-invoices' own permission ({@code invoicing-einvoices:READ}): the back office any correction's, the
 * customer (OWN) one issued to them, anything else 404. Handlers are public so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/einvoices")
@Tag(name = "E-invoices")
class EInvoiceAgreementPaperController {

    private final EInvoiceAgreementPaperService papers;
    private final CustomerService customers;

    EInvoiceAgreementPaperController(EInvoiceAgreementPaperService papers, CustomerService customers) {
        this.papers = papers;
        this.customers = customers;
    }

    @GetMapping(value = "/{id}/agreement/draft", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The agreement to adjust or replace an invoice (\"Biên bản thỏa thuận điều chỉnh / thay thế "
            + "hóa đơn\"), drafted from the correction {id} to print and sign; 404 EINVOICE_NOT_FOUND, 409 "
            + "EINVOICE_CORRECTION_NOT_APPLICABLE for an invoice that corrects nothing or a voided draft")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.ALL)
    public ResponseEntity<byte[]> draft(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return pdf(papers.agreement(id));
    }

    @GetMapping(value = "/me/{id}/agreement/draft", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The agreement of an adjustment or a replacement issued to the signed-in customer; 404 "
            + "EINVOICE_NOT_FOUND for any other invoice")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<byte[]> myDraft(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
        return pdf(papers.agreementOfCustomer(customerId, id));
    }

    private static ResponseEntity<byte[]> pdf(EInvoiceAgreementPaperService.Pdf pdf) {
        return PdfDownload.inline(pdf.fileName(), pdf.content());
    }
}

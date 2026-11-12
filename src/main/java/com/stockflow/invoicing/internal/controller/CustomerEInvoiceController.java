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
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.invoicing.internal.controller.dto.CustomerEInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceFileLinkResponse;
import com.stockflow.invoicing.internal.service.EInvoiceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in customer's own e-invoices on the portal (WF-05 E5; plan §1.4 "Khách xem hoặc tải hoá đơn của mình trên
 * portal"): the invoices issued to it, and a link to each one's PDF or XML.
 *
 * <p><b>The customer is resolved from the sign-in, never from the request.</b> There is no customer id to pass, so
 * there is none to forge; an invoice id that is another customer's, a draft's or nobody's answers the same 404
 * {@code EINVOICE_NOT_FOUND}, so the portal cannot be used to find out which invoices exist. Only issued invoices
 * are shown: a draft or a refused invoice is the seller's working paper.</p>
 *
 * <p>The same permission as the back office's list ({@code einvoices:READ}), held by CUSTOMER since
 * V20261120000600, with scope OWN here; every back-office handler of {@link EInvoiceController} refuses a portal
 * account. Handlers are public so the permission proxy advises them.</p>
 */
@RestController
@RequestMapping("/api/v1/einvoices/me")
@Tag(name = "E-invoices")
class CustomerEInvoiceController {

    private final EInvoiceQueryService queries;
    private final CustomerService customers;

    CustomerEInvoiceController(EInvoiceQueryService queries, CustomerService customers) {
        this.queries = queries;
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "The e-invoices issued to the signed-in customer, the newest invoice date first")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<CustomerEInvoiceResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(queries.issuedTo(ownCustomerId(user), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(InvoicingWebMapper::toCustomerResponse));
    }

    @GetMapping("/{id}/files/{kind}")
    @Operation(summary = "A short-lived link to the XML or PDF (kind: XML or PDF) of one of the signed-in customer's "
            + "own issued e-invoices; 404 EINVOICE_NOT_FOUND for any other")
    @RequiresPermission(resource = InvoicingResources.EINVOICES, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<EInvoiceFileLinkResponse> file(@PathVariable UUID id,
                                                      @PathVariable EInvoiceQueryService.FileKind kind,
                                                      @AuthenticatedUser CurrentUser user) {
        DownloadLink link = queries.customerFileLink(ownCustomerId(user), id, kind);
        return ApiResponse.ok(new EInvoiceFileLinkResponse(link.url(), link.expiresAt()));
    }

    private UUID ownCustomerId(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}

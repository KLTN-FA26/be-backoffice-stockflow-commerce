package com.stockflow.order.internal.controller;

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
import com.stockflow.order.internal.service.ContractPaperService;
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
 * The papers that follow a signed contract, as PDFs: its appendix (what changed since signing) and the record that
 * liquidates it. Read as the contract is: the back office under {@code sales-contracts:READ} and the caller's data
 * scope (out of scope is 404), the customer their own order's under {@code sales-orders:READ} OWN. Handlers are public
 * so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class ContractPaperController {

    private final ContractPaperService papers;
    private final CustomerService customers;

    ContractPaperController(ContractPaperService papers, CustomerService customers) {
        this.papers = papers;
        this.customers = customers;
    }

    @GetMapping(value = "/{orderId}/contract/appendix", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The contract's appendix (\"Phụ lục hợp đồng\"): every delivery window moved since signing, "
            + "what the contract says beside what it now says; 404 when nothing changed, 409 "
            + "SALES_CONTRACT_STATUS_INVALID before signing")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ResponseEntity<byte[]> appendix(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return pdf(papers.appendix(orderId));
    }

    @GetMapping(value = "/{orderId}/contract/liquidation", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The record liquidating the contract (\"Biên bản thanh lý hợp đồng\"): goods contracted, "
            + "delivered and returned, the money down to the balance; 409 SALES_CONTRACT_STATUS_INVALID while the "
            + "contract is not signed or still being performed")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ResponseEntity<byte[]> liquidation(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return pdf(papers.liquidation(orderId));
    }

    @GetMapping(value = "/me/{orderId}/contract/appendix", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The appendix of the contract of one of the signed-in customer's own orders")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<byte[]> myAppendix(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        return pdf(papers.appendixOfCustomer(ownCustomerId(user), orderId));
    }

    @GetMapping(value = "/me/{orderId}/contract/liquidation", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The record liquidating the contract of one of the signed-in customer's own orders")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<byte[]> myLiquidation(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        return pdf(papers.liquidationOfCustomer(ownCustomerId(user), orderId));
    }

    private UUID ownCustomerId(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }

    private static ResponseEntity<byte[]> pdf(ContractPaperService.Pdf pdf) {
        return PdfDownload.inline(pdf.fileName(), pdf.content());
    }
}

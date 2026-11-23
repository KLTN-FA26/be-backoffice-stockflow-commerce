package com.stockflow.procurement.internal.controller;

import com.stockflow.common.document.PdfDownload;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.procurement.internal.service.ReturnReceiptPaperService;
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
 * The stock-in voucher of a return receipt, as a PDF.
 *
 * <p>The warehouse's and the accountant's under the goods-receipt permission ({@code procurement-goods-receipts:READ}),
 * bounded by the caller's warehouses as the receipt itself is. The customer's under the permission it reads its
 * returns with ({@code sales-rmas:READ}, OWN) - the order module's code, named here as a string because the customer's
 * role holds no procurement permission and should not gain one for a copy of a voucher: it is a record of what came
 * back of the customer's own return, any other return is 404. Handlers are public so the permission proxy advises
 * them.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Return receipts", description = "Returned goods received by LPN into quarantine")
class ReturnReceiptPaperController {

    /** The code {@code OrderResources.RMAS} declares (and is named after): what a customer reads its returns with. */
    private static final String RMAS = "sales-rmas";

    private final ReturnReceiptPaperService papers;
    private final CustomerService customers;

    ReturnReceiptPaperController(ReturnReceiptPaperService papers, CustomerService customers) {
        this.papers = papers;
        this.customers = customers;
    }

    @GetMapping(value = "/goods-receipts/returns/{receiptId}/voucher", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The stock-in voucher of a confirmed return receipt (\"Phiếu nhập kho\", Mẫu số 01 - VT): a "
            + "line per SKU, by its cartons' labels against what was counted; 404 GOODS_RECEIPT_NOT_FOUND, 403 outside "
            + "the caller's warehouses, 409 INVALID_RECEIPT_TRANSITION while not confirmed")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.READ)
    public ResponseEntity<byte[]> voucher(@PathVariable UUID receiptId) {
        ReturnReceiptPaperService.Pdf pdf = papers.voucher(receiptId);
        return PdfDownload.inline(pdf.fileName(), pdf.content());
    }

    @GetMapping(value = "/returns/me/{returnId}/receipt-voucher", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The stock-in vouchers of the signed-in customer's own return: every receipt the warehouse "
            + "confirmed for it, one after another; 404 RETURN_REQUEST_NOT_FOUND (none, or another customer's), 404 "
            + "GOODS_RECEIPT_NOT_FOUND while nothing of it was confirmed received")
    @RequiresPermission(resource = RMAS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<byte[]> myVouchers(@PathVariable UUID returnId, @AuthenticatedUser CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
        ReturnReceiptPaperService.Pdf pdf = papers.vouchersOfCustomerReturn(customerId, returnId);
        return PdfDownload.inline(pdf.fileName(), pdf.content());
    }
}

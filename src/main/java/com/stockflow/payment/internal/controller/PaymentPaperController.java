package com.stockflow.payment.internal.controller;

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
import com.stockflow.payment.internal.service.PaymentPaperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The papers about money, as PDFs: the confirmation of a payment received, the confirmation of a refund paid, and the
 * record reconciling a customer's debt. Each is read under the permission of what it is drawn from - a payment
 * confirmation and a reconciliation under the installments' ({@code payment-installments}), a refund confirmation under
 * the refunds' ({@code payment-refunds}) - so whoever may see the figures on screen may print them, and nobody else.
 *
 * <p>The back office reads any customer's (scope ALL, never a portal customer); a customer reads their own under
 * {@code /me} (scope OWN), and anyone else's is 404 as if it did not exist. Handlers are public so the permission proxy
 * advises them.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payment papers", description = "Payment and refund confirmations, debt reconciliation - as PDF")
class PaymentPaperController {

    private final PaymentPaperService papers;
    private final CustomerService customers;

    PaymentPaperController(PaymentPaperService papers, CustomerService customers) {
        this.papers = papers;
        this.customers = customers;
    }

    @GetMapping(value = "/installments/{installmentId}/receipts/{receiptId}/confirmation",
            produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The confirmation of a payment received (\"Phiếu xác nhận thanh toán\") for one receipt's "
            + "payment of one installment; 404 INSTALLMENT_NOT_FOUND, PAYMENT_RECEIPT_NOT_FOUND (not of this "
            + "installment, or voided)")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    public ResponseEntity<byte[]> paymentConfirmation(@PathVariable UUID installmentId, @PathVariable UUID receiptId,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return pdf(papers.paymentConfirmation(installmentId, receiptId, null));
    }

    @GetMapping(value = "/installments/me/{installmentId}/receipts/{receiptId}/confirmation",
            produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The confirmation of a payment the signed-in customer made on one of their installments")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<byte[]> myPaymentConfirmation(@PathVariable UUID installmentId,
                                                        @PathVariable UUID receiptId,
                                                        @AuthenticatedUser CurrentUser user) {
        return pdf(papers.paymentConfirmation(installmentId, receiptId, customerOf(user)));
    }

    @GetMapping(value = "/refunds/records/{recordId}/confirmation", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The confirmation of a refund paid (\"Phiếu hoàn tiền\") for one refund record; 404 "
            + "REFUND_RECORD_NOT_FOUND, 409 REFUND_RECORD_ALREADY_VOIDED")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.READ, scope = DataScope.ALL)
    public ResponseEntity<byte[]> refundConfirmation(@PathVariable UUID recordId,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return pdf(papers.refundConfirmation(recordId, null));
    }

    @GetMapping(value = "/refunds/me/records/{recordId}/confirmation", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The confirmation of a refund paid to the signed-in customer")
    @RequiresPermission(resource = PaymentResources.REFUNDS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<byte[]> myRefundConfirmation(@PathVariable UUID recordId,
                                                       @AuthenticatedUser CurrentUser user) {
        return pdf(papers.refundConfirmation(recordId, customerOf(user)));
    }

    @GetMapping(value = "/installments/debt-reconciliation", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The record reconciling a customer's debt (\"Biên bản đối chiếu công nợ\") from a day to a "
            + "later one, not after today; 400 VALIDATION_FAILED, 404 CUSTOMER_NOT_FOUND")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    public ResponseEntity<byte[]> debtReconciliation(
            @RequestParam UUID customerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return pdf(papers.debtReconciliation(customerId, from, to));
    }

    @GetMapping(value = "/installments/me/debt-reconciliation", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The record reconciling the signed-in customer's own debt over a period")
    @RequiresPermission(resource = PaymentResources.INSTALLMENTS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<byte[]> myDebtReconciliation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticatedUser CurrentUser user) {
        return pdf(papers.debtReconciliation(customerOf(user), from, to));
    }

    private UUID customerOf(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }

    private static ResponseEntity<byte[]> pdf(PaymentPaperService.Pdf pdf) {
        return PdfDownload.inline(pdf.fileName(), pdf.content());
    }
}

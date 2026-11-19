package com.stockflow.invoicing.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One e-invoice as staff read it (WF-05 E2). {@code version} goes back with every change. The number, symbol, the tax
 * authority's code and the look-up code are there once it is issued; {@code overdue} says it is still to issue past its
 * deadline ({@code issueDueAt}: the end of the working day after {@code issueDate}).
 *
 * <p>On an adjustment or a replacement (WF-05 E4): {@code originalEInvoiceId} is the invoice it changes,
 * {@code correctionBasis} why (ERROR, RETURN, TRADE_DISCOUNT, or DELIVERY_FAILED - the system's full reduction of an
 * invoice whose delivery was corrected to a failure, which takes an agreement as optional evidence), {@code correctionReason} what it says is corrected,
 * {@code agreement} the written agreement with the buyer once attached - an ERROR correction cannot be issued without
 * it ({@code agreementRequired}) - {@code returnRequestId} the return a RETURN adjustment is for, and
 * {@code listedInvoiceIds} the other invoices a trade discount covers. An adjustment's lines are signed: below zero
 * reduces.</p>
 *
 * <p>{@code earlierPendingInvoice} (HĐ-2), on the invoice read by itself: the code of an invoice dated before this
 * draft and still to be issued - a draft, a rejected one or one at the provider - under the series this draft would be
 * sent under (the series in use for its year at the provider in use). Issued now, this draft would take a number before
 * it, and numbers should run in the order of the dates, so the screen says so; {@code warnings} then carries
 * {@code EARLIER_INVOICE_PENDING} too. A warning only: issuing is refused ({@code EINVOICE_OLDER_DRAFT_PENDING}) only
 * while the earlier invoice is at the provider. Null when nothing waits, when no series is in use for the draft's
 * year, on an invoice that is not a draft, and in the answers of the other operations.</p>
 *
 * <p>{@code deliveryFailedAt} (WF-05 HĐ-3): when the delivery this invoice was at the provider for was corrected to a
 * failure. Once the provider answers, an issued one is reduced in full ({@code correctionBasis} DELIVERY_FAILED on the
 * reduction) and a refused one is voided rather than sent again. Null on an invoice never marked - every adjustment,
 * and every invoice from before the mark existed - and again once the mark is taken back because the shipment turned
 * out delivered after all.</p>
 */
public record EInvoiceResponse(UUID id, String code, String type, String status, boolean adjusted, UUID orderId,
                               String orderNumber, String contractNumber, UUID shipmentId, String shipmentCode,
                               UUID deliveryBatchId, UUID customerId, UUID originalEInvoiceId, String provider,
                               String templateCode, String symbol, Integer invoiceNumber, String taxAuthorityCode,
                               String lookupCode, LocalDate issueDate, Instant issueDueAt, boolean overdue,
                               Instant signedAt, Party seller, Party buyer, String currency, String paymentMethod,
                               List<Line> lines, List<TaxRateTotal> totalsByRate, BigDecimal subtotal,
                               BigDecimal taxTotal, BigDecimal grandTotal, String amountInWords, List<String> warnings,
                               String rejectionCode, String rejectionMessage, Instant submittedAt, Instant issuedAt,
                               Instant voidedAt, String voidReason, boolean hasFiles, long version,
                               String correctionBasis, String correctionReason, boolean agreementRequired,
                               Agreement agreement, UUID returnRequestId, List<UUID> listedInvoiceIds,
                               String earlierPendingInvoice, Instant deliveryFailedAt) {

    public record Agreement(String number, LocalDate date) {
    }

    public record Party(String name, String taxCode, String address, String email) {
    }

    public record Line(UUID id, int lineNo, String kind, String feeKind, UUID orderLineId, String sku, String itemName,
                       String unit, int quantity, Integer unitsPerCarton, BigDecimal unitPrice, BigDecimal taxRate,
                       BigDecimal amount, BigDecimal taxAmount) {
    }

    public record TaxRateTotal(BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount) {
    }
}

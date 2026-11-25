package com.stockflow.invoicing.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One e-invoice as staff read it (WF-05 E2). {@code version} goes back with every change. The number, symbol, the tax
 * authority's code and the look-up code are there once it is issued; {@code overdue} says it is still to issue past its
 * deadline ({@code issueDueAt}: the end of {@code issueDate}, WF-05 Q-1).
 *
 * <p>On an adjustment or a replacement (WF-05 E4): {@code originalEInvoiceId} is the invoice it changes,
 * {@code correctionBasis} why (ERROR, RETURN, TRADE_DISCOUNT, or DELIVERY_FAILED - the system's full reduction of an
 * invoice whose delivery was corrected to a failure, which takes an agreement as optional evidence), {@code correctionReason} what it says is corrected,
 * {@code agreement} the written agreement with the buyer once attached - an ERROR correction cannot be issued without
 * it ({@code agreementRequired}) - {@code returnRequestId} the return a RETURN adjustment is for, and
 * {@code listedInvoiceIds} the other invoices a trade discount covers. An adjustment's lines are signed: below zero
 * reduces.</p>
 *
 * <p>{@code originalEInvoiceCode}, {@code originalEInvoiceSymbol} and {@code originalEInvoiceNumber} name the invoice
 * {@code originalEInvoiceId} points at the way its own {@code code}, {@code symbol} and {@code invoiceNumber} do, so the
 * screen can print "Hoá đơn gốc: 1C26TAA số 12" without a second read; the symbol and number are null while that
 * invoice is not issued, all three null on an original. {@code returnRmaNumber} is the RMA number of the return
 * {@code returnRequestId} names; null when it names none.</p>
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
 *
 * <p>WF-05 F-11, F-13, Q-1: {@code reversedAt} - when the invoice was reduced in full for a failed delivery; it is then
 * not corrected again (adjust and replace are refused, BE-36). {@code errorNoticeSupported} - whether a Mẫu 04/SS-HĐĐT
 * notice can be sent for it through its provider (none here can: it is filed outside the system, and the buyer is
 * never told it was sent). {@code issuedVia} - {@code PROVIDER}, or {@code PORTAL_RECORD} when the accountant recorded
 * it from the provider's portal (Q-8); null before it is issued. {@code deliveredOn} - the day of the delivery an
 * original invoices; {@code issueDate} is later on one issued late ({@code warnings} has {@code ISSUED_LATE}).
 * {@code voidReasonCode} - why a reduction of a failed delivery was voided ({@code UNSENDABLE_REVERSAL},
 * {@code DELIVERY_CORRECTED_BACK}).</p>
 *
 * <p>What the accountant may do now, decided by the rules the actions themselves apply, so a button the screen shows
 * is one the server accepts (audit r2 H-2, M-10):</p>
 * <ul>
 *   <li>{@code withdrawable} - "Rút" ({@code /withdrawal}) would be taken now, judged on the provider's last answer on
 *       record. False with {@code withdrawalBlockedBy}: {@code NOT_AT_PROVIDER} (not SUBMITTING), {@code PROVIDER_HOLDS}
 *       (the provider holds it, issued without its number yet or still processing - never withdrawn, decision Q-5:
 *       "Kiểm tra lại" only), {@code TOO_RECENT} (possible from {@code withdrawableFrom}), {@code
 *       PROVIDER_STATE_UNCLEAR} (the provider does not say it has no record of it - waited for, or recorded from its
 *       portal). {@code withdrawalEvidenceRequired}: the provider cannot confirm its result, so the withdrawal needs
 *       {@code supportReference}, the provider's support's written answer. The withdrawal still asks the provider again
 *       and may refuse what changed since.</li>
 *   <li>{@code externalReductionAllowed} - "Ghi hoá đơn giảm" ({@code /external-reductions}) is accepted: {@code
 *       externalReductionBases} lists {@code DELIVERY_FAILED} when a failed delivery's reduction may be recorded, and
 *       {@code RETURN} when a return's refunded goods wait on this replacement - {@code externalReductionReturns}
 *       names each, with the id {@code returnRequestId} takes; {@code RETURN} also on an original no provider can
 *       correct any more whose return adjustment was voided (audit r3 EINV-2); {@code ERROR} when a correction of an
 *       error of such an original, agreed in writing, was voided to be made on the provider's portal -
 *       {@code externalReductionErrors} names each voided draft, with the id {@code voidedAdjustmentId} takes, its
 *       code, what it corrects and what it takes off in all (below zero).</li>
 *   <li>{@code deliveryFailureReductionAllowed} (BE #423) - "Lập bản giảm do giao thất bại"
 *       ({@code /delivery-failure-reduction}) would draw the reduction now: an issued original, not reduced, still
 *       standing for its shipment, whose shipment is not delivered now, with no correction of it at the provider, and
 *       that the system can reduce - the command's own checks, run without writing. Unlike {@code deliveryFailedAt} it
 *       holds for an original issued before its delivery was corrected to a failure, the case the command exists
 *       for.</li>
 * </ul>
 *
 * <p>Audit r3 EINV-3 (decision §4.4 HĐ-1 step 3: "sau khi rút có dòng 'đang tra lại tới ngày ...'"): {@code watchUntil}
 * - until when the invoice's latest transaction let go (withdrawn, or refused on the provider's processing report
 * alone) is asked about again, daily, in case the provider issued it after all; {@code watchFoundAt} - when a
 * transaction of it let go, this one or an earlier one, was found issued after all (the accountants were told, {@code
 * DUPLICATE_RISK}; confirmation audit CF-9: an earlier one found is not hidden by a later one still watched). Both
 * null when no transaction of it was
 * let go. They stay on the invoice after it is reopened and sent again: the old transaction is watched all the
 * same.</p>
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
                               String earlierPendingInvoice, Instant deliveryFailedAt, Instant reversedAt,
                               boolean errorNoticeSupported, String issuedVia, LocalDate deliveredOn,
                               String voidReasonCode, boolean withdrawable, String withdrawalBlockedBy,
                               Instant withdrawableFrom, boolean withdrawalEvidenceRequired,
                               boolean externalReductionAllowed, List<String> externalReductionBases,
                               List<ExternalReductionReturn> externalReductionReturns, Instant watchUntil,
                               Instant watchFoundAt, List<ExternalReductionError> externalReductionErrors,
                               String originalEInvoiceCode, String originalEInvoiceSymbol,
                               Integer originalEInvoiceNumber, String returnRmaNumber,
                               boolean deliveryFailureReductionAllowed) {

    /** A return whose refunded goods wait on this replacement: {@code returnRequestId} is what the record takes. */
    public record ExternalReductionReturn(UUID returnRequestId, String rmaNumber) {
    }

    /**
     * A voided correction of an error of this original that may be recorded from the provider's portal (audit r3
     * EINV-2): {@code voidedAdjustmentId} is what the record takes; {@code grandTotal} is below zero.
     */
    public record ExternalReductionError(UUID voidedAdjustmentId, String code, String reason, BigDecimal grandTotal) {
    }

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

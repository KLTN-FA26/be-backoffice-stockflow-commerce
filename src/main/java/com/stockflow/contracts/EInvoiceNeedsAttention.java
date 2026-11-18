package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when an e-invoice that was sent to the provider will not be issued unless
 * somebody acts (WF-05 E5; plan §1.4 "Bị từ chối hoặc lỗi thì báo kế toán"):
 * <ul>
 *   <li>{@link Cause#REJECTED} - the provider or the tax authority refused it (a wrong tax code ...). Published in the
 *       transaction that records the refusal, once per submission.</li>
 *   <li>{@link Cause#STUCK_SUBMITTING} - it has waited for the provider's answer for longer than the configured time.
 *       Published once per submission.</li>
 *   <li>{@link Cause#UNKNOWN_TO_PROVIDER} - the provider no longer knows the transaction it was sent under, past the
 *       time it remembers one (WF-05 E3). It is not sent again on its own, since the same transaction id could become
 *       a second invoice: the accountant sends it as a new transaction. Published once per submission, when the
 *       invoice is marked.</li>
 *   <li>{@link Cause#DELIVERY_FAILED_AFTER_SUBMISSION} - the warehouse corrected the invoice's delivery to a failure
 *       after the invoice was issued (kltn-docs 21 §4.5), and the system could not reduce it in full itself because
 *       something corrects it already - an adjustment of it, issued or not, or a replacement being drawn up. It stands
 *       for goods that were not delivered until the accountant brings it to nothing by hand (TT 91 Điều 10). Published
 *       in the transaction that handles the correction, or that settles the provider's answer, once per invoice and
 *       submission.</li>
 *   <li>{@link Cause#DELIVERY_FAILED_AWAITING_PROVIDER} - the same correction while the invoice was still at the
 *       provider (WF-05 HĐ-3). Nothing is for the accountant to do: once the provider answers, the system reduces the
 *       invoice in full if it was issued ({@link Cause#DELIVERY_FAILED_REVERSAL_DRAWN_UP} follows) or voids it if it was
 *       refused. Told so that an invoice of a delivery that failed is not issued by hand meanwhile. Published in the
 *       transaction that handles the correction.</li>
 *   <li>{@link Cause#SIGNATURE_INVALID} - the provider issued it, but the seller's digital signature on the XML it
 *       handed back does not hold (HĐ-8). The invoice exists in law; it is not sent to the buyer
 *       ({@link EInvoiceIssued} is not published for it) until the accountant has it replaced. Published in the
 *       transaction that records it issued, once.</li>
 *   <li>{@link Cause#DELIVERY_FAILED_REVERSAL_DRAWN_UP} - the same correction of an issued invoice (the original or the
 *       replacement standing for the shipment, issued before the correction or after it), for which the system drew up
 *       the full reduction as a draft (21 §4.4; WF-05 HĐ-3). {@code einvoiceId} and {@code code} name that
 *       draft, which the accountant issues; {@code submittedAt} is null. Published in the transaction that draws it up,
 *       once.</li>
 * </ul>
 * For the first three the deadline of the working day after the invoice's date keeps running (NĐ 254/2026/NĐ-CP Phụ lục điểm 7).
 * Notification tells the people who issue invoices; nothing else listens.
 *
 * <p>Codes, ids and instants only: the provider's sentence about a refusal stays on the invoice, behind its
 * permission. Consumers receive it in-process; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param code          the invoice's internal code
 * @param rejectionCode the provider's code for the refusal; null for any other cause
 * @param submittedAt   when this submission was sent
 * @param issueDueAt    the instant the invoice must have been issued by
 */
public record EInvoiceNeedsAttention(UUID einvoiceId, String code, UUID orderId, String orderNumber, Cause cause,
                                     String rejectionCode, Instant submittedAt, Instant issueDueAt) {

    public enum Cause {
        REJECTED, STUCK_SUBMITTING, UNKNOWN_TO_PROVIDER, DELIVERY_FAILED_AFTER_SUBMISSION, SIGNATURE_INVALID,
        DELIVERY_FAILED_REVERSAL_DRAWN_UP, DELIVERY_FAILED_AWAITING_PROVIDER
    }
}

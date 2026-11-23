package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
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
 *   <li>WF-05 full fix (decisions of 2026-11-23), one cause per new situation, each published once per invoice and
 *       submission:
 *       <ul>
 *         <li>{@link Cause#DUPLICATE_RISK} - an invoice withdrawn (UNKNOWN, with the provider support's answer) or
 *             refused by its words turned out ISSUED at the provider when the daily watch asked again within 30 days
 *             (BE-07, Q-5); the provider's invoice is recorded on it.</li>
 *         <li>{@link Cause#PROVIDER_RESULT_OVERDUE} - SUBMITTING past the time the provider can still answer; the
 *             accountant records the result from the provider's portal (BE-08, Q-8).</li>
 *         <li>{@link Cause#REDUCE_ON_PROVIDER_PORTAL} - the accountant voided a failed delivery's reduction as unsendable;
 *             the reduction is made on the old provider's portal and recorded (BE-10, Q-8).</li>
 *         <li>{@link Cause#DELIVERY_FAILED_NOT_REVERSED} - an issued invoice in force stands for a delivery that is not
 *             delivered now and was not reduced; "reduce for the failed delivery" is for the accountant (BE-11).</li>
 *         <li>{@link Cause#SERIES_MISMATCH} - the provider's look-up shows a template code or symbol other than the one
 *             sent; the invoice is not recorded ISSUED (BE-30).</li>
 *         <li>{@link Cause#ISSUED_LATE} - issued on a day after its delivery day (Q-1, Q-10): informational;
 *             {@code deliveredOn} and {@code issueDate} are set.</li>
 *         <li>{@link Cause#REPLACEMENT_NOT_REDUCIBLE} - a delivery invoiced by a replacement failed, and the provider
 *             does not adjust a replacement: no reduction was drawn up (BE-09, Q-9).</li>
 *         <li>{@link Cause#AUTO_ISSUE_HELD} - audit r2 M-2: a draft original the automatic issuing holds back - a warning
 *             that needs a person (decision §1.5 step 5), or a refusal the automatic issuing met when it tried. Told at
 *             once, not at the deadline warning four hours before midnight (kltn-docs 21 §4.2 step 3): the invoice is
 *             due by the end of its delivery day (Q-1). Once per draft and reason, which {@code rejectionCode} carries -
 *             the draft's warnings as stored ({@code TAX_CLASS_UNKNOWN}, ...) or the error code
 *             ({@code EINVOICE_SERIES_NOT_CONFIGURED}, ...); {@code submittedAt} is null.</li>
 *       </ul></li>
 * </ul>
 * For the first three the invoice's deadline - the end of its date (NĐ 254/2026/NĐ-CP Điều 9 khoản 1; WF-05 Q-1) -
 * keeps running.
 * Notification tells the people who issue invoices; nothing else listens.
 *
 * <p>Codes, ids and instants only: the provider's sentence about a refusal stays on the invoice, behind its
 * permission. Consumers receive it in-process; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param code          the invoice's internal code
 * @param rejectionCode the provider's code for the refusal; for {@link Cause#AUTO_ISSUE_HELD} the reason it is held;
 *                      null for any other cause
 * @param submittedAt   when this submission was sent
 * @param issueDueAt    the instant the invoice must have been issued by; never null
 * @param deliveredOn   the delivery day (Asia/Ho_Chi_Minh) for {@link Cause#ISSUED_LATE}; null otherwise and in
 *                      publications stored before
 * @param issueDate     the invoice's date for {@link Cause#ISSUED_LATE}; null otherwise and in publications stored before
 */
public record EInvoiceNeedsAttention(UUID einvoiceId, String code, UUID orderId, String orderNumber, Cause cause,
                                     String rejectionCode, Instant submittedAt, Instant issueDueAt,
                                     LocalDate deliveredOn, LocalDate issueDate) {

    /** Every cause but {@link Cause#ISSUED_LATE}, and as published before the dates were carried. */
    public EInvoiceNeedsAttention(UUID einvoiceId, String code, UUID orderId, String orderNumber, Cause cause,
                                  String rejectionCode, Instant submittedAt, Instant issueDueAt) {
        this(einvoiceId, code, orderId, orderNumber, cause, rejectionCode, submittedAt, issueDueAt, null, null);
    }

    public enum Cause {
        REJECTED, STUCK_SUBMITTING, UNKNOWN_TO_PROVIDER, DELIVERY_FAILED_AFTER_SUBMISSION, SIGNATURE_INVALID,
        DELIVERY_FAILED_REVERSAL_DRAWN_UP, DELIVERY_FAILED_AWAITING_PROVIDER,
        DUPLICATE_RISK, PROVIDER_RESULT_OVERDUE, REDUCE_ON_PROVIDER_PORTAL, DELIVERY_FAILED_NOT_REVERSED,
        SERIES_MISMATCH, ISSUED_LATE, REPLACEMENT_NOT_REDUCIBLE, AUTO_ISSUE_HELD
    }
}

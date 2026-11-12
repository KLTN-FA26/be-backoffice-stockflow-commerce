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
 * </ul>
 * Either way the deadline of the working day after the invoice's date keeps running (NĐ 254/2026/NĐ-CP Phụ lục điểm 7).
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

    public enum Cause { REJECTED, STUCK_SUBMITTING, UNKNOWN_TO_PROVIDER }
}

package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when staff ask for an issued e-invoice to be e-mailed to its buyer again,
 * to the customer's invoice e-mail as it is now (WF-05 audit r2 M-1; decision §1.5 step 3 and §3 HĐ-8: "Sale được
 * báo, bổ sung e-mail → gửi lại"; kltn-docs 21 BR-06). The first mail of an invoice issued while the customer had no
 * invoice e-mail was skipped for good; once Sales has recorded one, this is how the invoice reaches it - and how it
 * reaches a corrected address after a mail went to a wrong one. Published in the transaction that records the request
 * (audited), once per request.
 *
 * <p>Notification sends it as a new message ({@code customer-mail:einvoice.resent:<einvoiceId>:<requestedAt>}),
 * through the same delivery, log and retry as the first ({@code CustomerEmailDelivery}, {@code CustomerEmailRetry}),
 * addressed and worded from invoicing when it is sent ({@code notification.api.EInvoiceDirectory#mailAgain}): ids and
 * codes only here, never the address - an event is kept in {@code event_publication} long after it is delivered.</p>
 *
 * <p>Consumers receive it in-process; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param code        the invoice's internal code
 * @param requestedAt when it was asked for; with the invoice, what names the message
 * @param requestedBy who asked
 */
public record EInvoiceMailRequested(UUID einvoiceId, String code, UUID orderId, String orderNumber, UUID customerId,
                                    Instant requestedAt, UUID requestedBy) {
}

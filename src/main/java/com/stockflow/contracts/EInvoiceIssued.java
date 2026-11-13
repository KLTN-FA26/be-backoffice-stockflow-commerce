package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when an e-invoice becomes ISSUED (WF-05 E5; plan §1.4): the provider gave it
 * its number and the tax authority's code, and its XML and PDF are stored. Published in the transaction that records
 * the issue, once per invoice - an invoice is issued once.
 *
 * <p>Notification tells the buyer: the invoice is on the portal, and it is e-mailed with its files to the address the
 * invoice names. The event carries ids and codes only. The buyer's address, the lookup code and the files are read
 * from invoicing when the message is written ({@code notification.api.EInvoiceDirectory}): an event is stored in
 * {@code event_publication} long after it is delivered, and none of those belongs there.</p>
 *
 * <p>Consumers receive it in-process; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param code       the invoice's internal code
 * @param shipmentId the delivery an original invoices; null for an invoice that is not about one delivery
 * @param customerId the buyer
 */
public record EInvoiceIssued(UUID einvoiceId, String code, UUID orderId, String orderNumber, UUID shipmentId,
                             UUID customerId, Instant issuedAt) {
}

package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when an e-invoice that is still to be issued comes close to its deadline
 * (WF-05 E2): an invoice is signed and sent for the tax authority's code no later than the working day after its date
 * (NĐ 254/2026/NĐ-CP Phụ lục điểm 7), and its date is the day of delivery, not the day the accountant gets to it.
 *
 * <p>Published once per invoice. Notification tells the people who issue invoices; nothing else listens.</p>
 *
 * <p>Codes, ids and dates only - nothing anybody typed. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param code       the invoice's internal code
 * @param issueDate  the invoice's date: the day the goods were delivered
 * @param issueDueAt the instant it must have been issued by
 */
public record EInvoiceIssueDue(UUID einvoiceId, String code, UUID orderId, String orderNumber, LocalDate issueDate,
                               Instant issueDueAt) {
}

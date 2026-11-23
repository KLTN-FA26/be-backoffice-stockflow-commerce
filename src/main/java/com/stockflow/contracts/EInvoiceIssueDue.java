package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when an e-invoice that is still to be issued comes close to its deadline
 * (WF-05 E2): an invoice is issued by the end of its date (NĐ 254/2026/NĐ-CP Điều 9 khoản 1; WF-05 Q-1 - the next
 * working day of Phụ lục điểm 7 is for night sales without automatic issuing), and an original's date is the day of
 * delivery, not the day the accountant gets to it.
 *
 * <p>Published once per invoice ahead of its deadline ({@code overdueOn} null), and then, while it is still not issued
 * past its deadline, once a day ({@code overdueOn} the day of the reminder; HĐ-2): an invoice stuck at the provider or
 * left in draft is not forgotten after a single message. Notification tells the people who issue invoices; nothing
 * else listens.</p>
 *
 * <p>Codes, ids and dates only - nothing anybody typed. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param code       the invoice's internal code
 * @param issueDate  the invoice's date: the day the goods were delivered
 * @param issueDueAt the instant it must have been issued by
 * @param overdueOn  for a reminder of an invoice already past its deadline, the day (Vietnam time) it is reminded of;
 *                   null for the warning ahead of the deadline, and in publications from before reminders existed
 */
public record EInvoiceIssueDue(UUID einvoiceId, String code, UUID orderId, String orderNumber, LocalDate issueDate,
                               Instant issueDueAt, LocalDate overdueOn) {

    /** The warning ahead of the deadline, as every publication was before overdue reminders existed. */
    public EInvoiceIssueDue(UUID einvoiceId, String code, UUID orderId, String orderNumber, LocalDate issueDate,
                            Instant issueDueAt) {
        this(einvoiceId, code, orderId, orderNumber, issueDate, issueDueAt, null);
    }
}

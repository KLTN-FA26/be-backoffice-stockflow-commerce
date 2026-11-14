package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when a notice that an issued e-invoice names its buyer wrongly is recorded
 * (WF-05 E4; Mẫu 04/SS-HĐĐT, TT 91/2026/TT-BTC Điều 10 khoản 1 điểm a as read in plans/E0-legal.md row 1): the buyer's
 * name or address on the invoice is wrong, its tax code right, and no new invoice is drawn up. The law then asks for two
 * things side by side - the buyer is told, and the tax authority is notified on the form. This event is the first; the
 * second is the provider call that follows it. Published in the transaction that records the notice, once per notice,
 * whatever the tax authority answers later: the invoice is wrong either way, and the buyer keeps it.
 *
 * <p>Not published for a notice sent beside an adjustment or a replacement: the buyer is told of that correction by
 * its own issued invoice ({@link EInvoiceIssued}).</p>
 *
 * <p>Notification tells the buyer on the portal and by e-mail. The event carries ids and codes only; what the notice
 * says - the right name and address, the reason - and where it goes are read from invoicing when the message is
 * written ({@code notification.api.EInvoiceDirectory}), because an event is stored in {@code event_publication} long
 * after it is delivered and none of those belongs there.</p>
 *
 * <p>Consumers receive it in-process; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param noticeId   the notice
 * @param einvoiceId the issued invoice it is about
 * @param code       the invoice's internal code
 * @param customerId the buyer
 */
public record EInvoiceErrorNoticeRecorded(UUID noticeId, UUID einvoiceId, String code, UUID orderId,
                                          String orderNumber, UUID customerId, Instant recordedAt) {
}

package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when the refund of a return was approved and the decrease adjustment the
 * law asks for (TT 91/2026/TT-BTC Điều 10 khoản 5 điểm c.1) could not be drawn up for all of it by itself (WF-05 E4):
 * <ul>
 *   <li>{@link Cause#NOT_ON_AN_ISSUED_INVOICE} - some refunded pieces are on no issued invoice in force that could
 *       take them: their delivery's invoice is not issued yet, it is being replaced, or a piece of a carton is not
 *       worth whole dong. What could be adjusted was drawn up; the rest is left to the accountant.</li>
 *   <li>{@link Cause#REFUSED} - the invoice's rules refused the adjustment ({@code reasonCode}); nothing was drawn
 *       up.</li>
 * </ul>
 * Either way the accountant draws the adjustment up by hand, naming the return. Published once per return, from the
 * transaction that handled its {@link ReturnResolved}; a return already adjusted publishes nothing. Notification tells
 * the people who issue invoices; nothing else listens.
 *
 * <p>Codes, counts and ids only. Consumers receive it in-process; see {@code package-info.java} for the rules on
 * changing this contract.</p>
 *
 * @param orderLines the order lines with refunded pieces left unadjusted (for {@code REFUSED}: every refunded line)
 * @param pieces     the refunded pieces left unadjusted, across those lines
 * @param reasonCode the error code of the refusal; null for {@code NOT_ON_AN_ISSUED_INVOICE}
 */
public record EInvoiceReturnUnadjusted(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber,
                                       Cause cause, int orderLines, long pieces, String reasonCode,
                                       Instant resolvedAt) {

    public enum Cause { NOT_ON_AN_ISSUED_INVOICE, REFUSED }
}

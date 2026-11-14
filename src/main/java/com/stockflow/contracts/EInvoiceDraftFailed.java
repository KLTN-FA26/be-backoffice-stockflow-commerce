package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when a delivery was recorded and no draft e-invoice could be drawn up for
 * it (WF-05 E2): the seller is not configured, the shipment delivered nothing an invoice can carry, the customer is
 * gone. Every delivery is to be invoiced (NĐ 254/2026/NĐ-CP Điều 9 khoản 3), so a delivery without its draft must be
 * in front of somebody, not in a log.
 *
 * <p>Also published, with no {@code shipmentId}, when an order's undelivered rest was closed and the invoice of the
 * rest of its shipping fee - the part no delivery's invoice carries - could not be drawn up: that amount is owed and
 * invoiced the same way, from the order rather than a shipment.</p>
 *
 * <p>Notification tells the people who draw invoices up; the draft is drawn up by hand once the cause is gone
 * ({@code POST /api/v1/einvoices}). Codes and ids only - nothing anybody typed. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param shipmentId the delivered shipment; null for the invoice of an order's shipping fee rest
 * @param reasonCode the error code the draft was refused with ({@code SELLER_PROFILE_UNAVAILABLE}, ...)
 */
public record EInvoiceDraftFailed(UUID orderId, String orderNumber, UUID shipmentId, String reasonCode) {
}

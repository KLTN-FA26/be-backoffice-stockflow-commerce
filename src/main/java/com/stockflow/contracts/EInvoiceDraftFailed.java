package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when a delivery was recorded and no draft e-invoice could be drawn up for
 * it (WF-05 E2): the seller is not configured, the shipment delivered nothing an invoice can carry, the customer is
 * gone. Every delivery is to be invoiced (NĐ 254/2026/NĐ-CP Điều 9 khoản 3), so a delivery without its draft must be
 * in front of somebody, not in a log.
 *
 * <p>Notification tells the people who draw invoices up; the draft is drawn up by hand once the cause is gone
 * ({@code POST /api/v1/einvoices}). Codes and ids only - nothing anybody typed. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param reasonCode the error code the draft was refused with ({@code SELLER_PROFILE_UNAVAILABLE}, ...)
 */
public record EInvoiceDraftFailed(UUID orderId, String orderNumber, UUID shipmentId, String reasonCode) {
}

package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the system would have cancelled an order on its own - a payment failed,
 * an unpaid hold ran out - but printing had already started (decision 2026-10-17). Nothing is cancelled: staff decide,
 * by hand, what the agreed fee, the blanks and the printed goods become, and cancel from the back office if they must.
 * The notification module tells Sales and the order coordinators.
 *
 * <p>Ids and codes only, no personal data: {@code reasonCode} is the cancellation reason the system would have used
 * ({@code PAYMENT_FAILED}, {@code PAYMENT_NOT_RECEIVED}).</p>
 */
public record OrderCancellationReferred(UUID orderId, String orderNumber, String reasonCode, Instant occurredAt) {
}

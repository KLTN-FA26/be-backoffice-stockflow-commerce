package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer asked to cancel their order (kltn-docs 17 §4.8 step 28: "Hệ thống báo Sale"): Sales decide it on the
 * cancellation requests page. Published after the request is saved; carries ids and codes only, not the customer's
 * note.
 *
 * @param requestId   the request, PENDING
 * @param orderId     its order
 * @param orderNumber the order's number, for the notice
 * @param reasonCode  the reason the customer chose ({@code CancellationReasonCode})
 * @param requestedAt when it was asked
 */
public record OrderCancellationRequested(UUID requestId, UUID orderId, String orderNumber, String reasonCode,
                                         Instant requestedAt) {
}

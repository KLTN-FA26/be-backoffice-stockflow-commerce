package com.stockflow.notification.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Sends a production order's latest message to its producer again, as a new generation with fresh links (4A-3): for a
 * message whose retries are spent, an address the coordinator corrected, or links that died - expired, or the links key
 * rotated. Called by the order module, which decides whether the production order may still be re-sent; this decides
 * whether its message may.
 */
public interface ProductionOrderResends {

    /**
     * In the caller's transaction: records the new generation, and the message goes after commit.
     *
     * @param at when it is asked for; the new links work for the configured time from it
     * @return which message goes again and its generation
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCTION_ORDER_RESEND_NOT_ALLOWED} when no message
     *         was attempted yet, or the latest is still being sent or retried
     */
    Resend resend(UUID productionOrderId, Instant at);

    /**
     * @param message    {@code SENT} or {@code SAMPLE_APPROVED}
     * @param generation the message's new generation (the first is 0)
     */
    record Resend(String message, int generation) {
    }
}

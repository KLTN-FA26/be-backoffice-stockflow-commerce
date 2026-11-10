package com.stockflow.notification.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Staff asked for a production order's latest message to its producer to go again (4A-3): published inside
 * notification by {@code ProductionOrderMailResends} and delivered by {@code ProductionOrderMailListener} after commit,
 * so a re-send is retried like any message. Notification's own event, not a contract: no other module reads it.
 *
 * @param kind        {@code SENT} or {@code SAMPLE_APPROVED}: which message goes again
 * @param generation  the new generation of that message; a retry of this event is the same message, and an event of an
 *                    older generation finds a newer one and does nothing
 * @param requestedAt when it was asked for: the new links work for the configured time from here
 */
public record ProductionOrderMailResend(UUID eventId, UUID productionOrderId, String kind, int generation,
                                        Instant requestedAt) {
}

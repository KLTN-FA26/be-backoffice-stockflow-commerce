package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a production order that was sent ends unfinished: cancelled before mass
 * printing, or closed after it (kltn-docs 17 §4.3 steps 12-13, BR-06) - once per production order. The
 * <b>notification</b> module tells the producer to stop, through the channel the production order went by. A production
 * order never sent is not published: its producer has nothing to stop.
 *
 * <p>Facts only, as {@link ProductionOrderSent}: the coordinator's reason is read when the e-mail is written. Consumers
 * receive it in-process through {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on
 * changing this contract.</p>
 *
 * @param outcome {@code CANCELLED} or {@code CLOSED}
 */
public record ProductionOrderStopped(UUID eventId, UUID productionOrderId, String code, String outcome,
                                     Instant stoppedAt) {
}

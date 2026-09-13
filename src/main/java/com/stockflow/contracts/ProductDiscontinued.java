package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>product</b> module when a product master row is discontinued (WBS 3.1.8.1).
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry. See {@code package-info.java} for the
 * rules on changing this contract.
 *
 * <p>No listener exists yet — {@code catalog}, {@code procurement} and {@code reporting} are still
 * skeletons — so this event is inert today, the same position {@code ProductApproved} was in
 * before this story.</p>
 */
public record ProductDiscontinued(UUID productId, Instant discontinuedAt) {
}

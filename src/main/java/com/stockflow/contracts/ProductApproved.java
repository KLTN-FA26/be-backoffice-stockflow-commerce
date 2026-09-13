package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>product</b> module when a product master row is approved (WBS 3.1.1.3,
 * BR-PRD-003). Consumers receive it in-process through {@code @ApplicationModuleListener};
 * delivery is made durable by Spring Modulith's event publication registry. See
 * {@code package-info.java} for the rules on changing this contract.
 *
 * <p>No listener exists yet — {@code catalog}, {@code design} and {@code reporting} are still
 * skeletons — so this event is inert today, the same position {@code OrderPlaced} was in before
 * {@code notification} existed.</p>
 */
public record ProductApproved(UUID productId, UUID approvedBy, UUID submittedBy) {
}

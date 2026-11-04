package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Which orders the nightly settlement looks at (G3-2): delivered orders whose return window may have closed, and
 * completed or refunded orders old enough to close. Bounded batches; the aggregate decides each one.
 */
public interface SettlementCandidates {

    /** DELIVERED orders delivered at or before {@code deliveredBy}, oldest first. */
    List<UUID> deliveredBy(Instant deliveredBy, int limit);

    /** COMPLETED orders completed, and REFUNDED / PARTIALLY_REFUNDED ones refunded, at or before {@code settledBy}. */
    List<UUID> settledBy(Instant settledBy, int limit);

    /** Whether a return request of the order is still open (BR-ORD-005: it keeps the order from completing). */
    boolean hasOpenReturn(UUID orderId);
}

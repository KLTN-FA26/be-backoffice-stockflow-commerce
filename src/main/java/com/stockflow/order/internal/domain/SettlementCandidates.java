package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Which orders the nightly settlement looks at (G3-2): delivered orders whose return window may have closed, and
 * completed or refunded orders old enough to close. Bounded batches; the aggregate decides each one.
 *
 * <p>{@code skip}: the candidates a run already looked at and left as they were (still owing on their installments, a
 * return open). Whether an order still owes is payment's to say (4B-10: the order keeps no money of its own), so the
 * query cannot leave those out; skipping them keeps one run from reading the same refused orders again and again while
 * the ones behind them wait.</p>
 */
public interface SettlementCandidates {

    /** DELIVERED orders delivered at or before {@code deliveredBy}, oldest first, the first {@code skip} left out. */
    List<UUID> deliveredBy(Instant deliveredBy, int skip, int limit);

    /**
     * COMPLETED orders completed, and REFUNDED / PARTIALLY_REFUNDED ones refunded, at or before {@code settledBy}, the
     * first {@code skip} left out.
     */
    List<UUID> settledBy(Instant settledBy, int skip, int limit);

    /** Whether a return request of the order is still open (BR-ORD-005: it keeps the order from completing). */
    boolean hasOpenReturn(UUID orderId);
}

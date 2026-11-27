package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Which orders the nightly settlement looks at (G3-2): delivered orders whose return window may have closed, and
 * completed or refunded orders old enough to close. Bounded batches; the aggregate decides each one.
 *
 * <p>{@code skip}: the candidates a run already looked at and left as they were (still owing on their installments, a
 * refund still to make to the customer - TT-4 - or a return open). Whether money is owed either way is payment's to
 * say (4B-10: the order keeps no money of its own), so the query cannot leave those out; skipping them keeps one run
 * from reading the same refused orders again and again while the ones behind them wait.</p>
 */
public interface SettlementCandidates {

    /**
     * DELIVERED orders whose return window may have closed by {@code now}, oldest delivery first, the first {@code skip}
     * left out: delivered at least the window before - each order's own window, the days its live contract agreed
     * ({@code sales_contract.return_window_days}, WF-05 TH-1), else {@code defaultWindowDays}. A coarse filter, never
     * finer than {@link Order#complete}, which decides in calendar days. An order whose completion is held (WF-05 GH-2)
     * is left out: the run would refuse it, and it is found again the night after its hold is released.
     */
    List<UUID> deliveredPastWindow(Instant now, int defaultWindowDays, int skip, int limit);

    /**
     * COMPLETED orders completed, and REFUNDED / PARTIALLY_REFUNDED ones refunded, at or before {@code settledBy}, the
     * first {@code skip} left out - and RETURNED / PARTIALLY_FULFILLED ones whose deliveries ended at or before it with
     * a shipment brought back or lost (WF-05 BE-45, audit r2 contract M-6: nothing made refundable, they close without
     * entering the refund branch, Order#closeWithoutRefund). Held orders (WF-05 Q-17) are left out: closing refuses
     * them. A superset otherwise: the return windows of the refund branch (Q-16 = b), whether money was made
     * refundable, and the rest of the money are the aggregate's and the service's to decide.
     */
    List<UUID> settledBy(Instant settledBy, int skip, int limit);

    /**
     * WF-05 Q-6 (BE-06): CANCELLED orders whose live contract is still SIGNED - cancelled after signing - the first
     * {@code skip} left out. Their contract is liquidated once the cancellation's money is settled.
     */
    List<UUID> cancelledWithSignedContract(int skip, int limit);

    /** Whether a return request of the order is still open (BR-ORD-005: it keeps the order from completing). */
    boolean hasOpenReturn(UUID orderId);

    /**
     * Whether the order's automatic completion is paused while a delivery is verified (WF-05 GH-2,
     * {@code ordering.order_completion_hold}): read under the order's lock, which holding and releasing take too.
     */
    boolean completionHeld(UUID orderId);
}

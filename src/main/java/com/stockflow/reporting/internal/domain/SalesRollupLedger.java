package com.stockflow.reporting.internal.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: where the rollups and the record of what was applied to them live. Implemented over JDBC in
 * {@code internal.repository}; the rules in {@link SalesRollups} are tested against an in-memory fake.
 */
public interface SalesRollupLedger {

    /**
     * Records that {@code type} was applied to {@code orderId}, keeping what the order contributed.
     *
     * @return true if this call recorded it, false if it was recorded before (and nothing was changed)
     */
    boolean markApplied(UUID orderId, OrderEventType type, OrderContribution contribution);

    /** What the PLACED event of this order added; empty if it was never applied. */
    Optional<OrderContribution> placedContribution(UUID orderId);

    /**
     * Adds ({@code sign} = 1) or removes ({@code sign} = -1) one order: its day's order count and revenue, and each
     * line's units and revenue on its SKU's row for that day. Rows are created on first use.
     */
    void apply(OrderContribution contribution, int sign);

    /**
     * Forgets everything rolled up for the orders placed on {@code day}: both summaries and the order ledger rows. The
     * return adjustments of that day are kept: they are the record a rebuild applies again ({@link #returnsOf}).
     */
    void clearDay(LocalDate day);

    /**
     * Records that a return's money ({@code event}: its refund, or - before team plan 4D-3 - the withdrawal of its
     * replacement) was taken off the rollups, keeping what it took: the order's placed day, the value and units.
     *
     * @return true if this call recorded it, false if it was recorded before (and nothing was changed)
     */
    boolean markReturnApplied(UUID returnRequestId, String event, OrderContribution taken);

    /**
     * Takes a return's credited value off its day's revenue, and each line's units and revenue off its SKU's row: the
     * order count stays, the sale happened. The rows exist already, made by the order's placement.
     */
    void applyReturn(OrderContribution taken);

    /** The return adjustments recorded on the orders placed on {@code day}, for a rebuild to apply again. */
    java.util.List<OrderContribution> returnsOf(LocalDate day);

    /**
     * The closures recorded on {@code orderId}, in the order they were applied: units in-house production closed out
     * of an order line (a short completion, S6) while it existed. Nothing records one any more.
     */
    java.util.List<OrderContribution> closuresOfOrder(UUID orderId);

    /** The closures recorded on the orders placed on {@code day}, for a rebuild to apply again. */
    java.util.List<OrderContribution> closuresOf(LocalDate day);

    /** Whether the cancellation of {@code orderId} was applied (or recorded as never counted). */
    boolean cancellationApplied(UUID orderId);
}

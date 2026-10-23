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

    /** Forgets everything rolled up for the orders placed on {@code day}: both summaries and the ledger rows. */
    void clearDay(LocalDate day);
}

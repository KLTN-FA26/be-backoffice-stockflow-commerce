package com.stockflow.reporting.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Keeps a rebuild from interleaving with the listeners. The rebuild of a day deletes that day's rows and replays its
 * orders; a listener applying an order meanwhile could be wiped (or double-counted). Both take a transaction-level
 * Postgres advisory lock on one key: listeners the <i>shared</i> mode, so they never wait for one another, the rebuild
 * the <i>exclusive</i> one, so it waits for the listeners in flight and holds the new ones back until its day commits.
 * The lock is released with the transaction; it cannot leak.
 */
@Repository
public class SalesRollupLock {

    /** Arbitrary, but fixed for the lifetime of the schema: a hash of "reporting.sales-rollup". */
    private static final long KEY = 0x5245504F52544C31L;

    private final JdbcTemplate jdbc;

    SalesRollupLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Held by a listener applying one order. Call inside the transaction that writes. */
    public void shared() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock_shared(?)", Object.class, KEY);
    }

    /** Held by the rebuild of one day. Call inside the transaction that writes. */
    public void exclusive() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, KEY);
    }
}

package com.stockflow.inventory.api;

/**
 * The status a putaway or a reclassification gives the units it moves (docs 05, 20).
 *
 * <p>Deliberately not the whole stock status set: DAMAGED and EXPIRED come from handling and from
 * the calendar, not from a decision another module makes about received goods.</p>
 */
public enum StockDisposition {
    /** Put away: on sale at a bin. */
    AVAILABLE,
    /** On hold apart from good stock, waiting for a decision (docs 20 §4.4). */
    QUARANTINE,
    /** Condemned; waiting to leave stock. */
    BLOCKED
}

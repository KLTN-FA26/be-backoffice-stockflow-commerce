package com.stockflow.inventory.api;

/**
 * The status a QC decision or a putaway gives the units it moves (docs 03 §5.2, 05).
 *
 * <p>Deliberately not the whole stock status set: DAMAGED and EXPIRED come from handling and from
 * the calendar, not from a decision another module makes about received goods.</p>
 */
public enum StockDisposition {
    /** Put away after QC accepted it, or for an item that needs no QC. */
    AVAILABLE,
    /** QC put it on hold, waiting for a second decision. */
    QUARANTINE,
    /** QC rejected it; waiting to go back to the supplier. */
    BLOCKED
}

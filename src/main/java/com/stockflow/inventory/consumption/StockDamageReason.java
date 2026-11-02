package com.stockflow.inventory.consumption;

/**
 * Why a picker found units damaged (BR-STK-003), as fulfillment sends it. The same values as inventory's own reason;
 * a separate type because nothing of {@code internal} may appear on this interface.
 */
public enum StockDamageReason {
    /** Broken or cracked. */
    BROKEN,
    /** Crushed, dented or deformed. */
    DEFORMED,
    /** Wet, damp or mouldy. */
    WET_OR_MOULDY,
    /** Dirty, stained or misprinted. */
    DIRTY_OR_MISPRINTED,
    /** Needs a note. */
    OTHER
}

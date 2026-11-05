package com.stockflow.inventory.internal.domain;

/**
 * What a warehouse manager decides for damaged units (docs 03 §5: DAMAGED → written off). Mapped
 * {@code EnumType.STRING} onto {@code inventory.stock_damage_disposition.disposition_type}.
 */
public enum DamageDisposition {

    /** Destroyed: a DAMAGED stock adjustment is posted and on-hand falls; the destruction record is numbered. */
    WRITE_OFF,

    /**
     * Going back to the supplier. Only the intent is recorded: the units stay DAMAGED in quarantine, counted and out
     * of sale, until procurement's return flow ships them (it has none yet; {@code StockDamageDisposed} is its hook).
     */
    RETURN_TO_SUPPLIER,

    /** Re-inspected and found fine (a dented outer carton, sound cups): back onto a bin as AVAILABLE. */
    RESTORE;

    /** Whether the decision must say why: putting goods back on sale or sending them back is never self-evident. */
    public boolean needsNote() {
        return this != WRITE_OFF;
    }
}

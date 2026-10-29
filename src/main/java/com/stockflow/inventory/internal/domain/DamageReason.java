package com.stockflow.inventory.internal.domain;

/**
 * Why units were found damaged (BR-STK-003). Mapped {@code EnumType.STRING} onto
 * {@code inventory.stock_damage_report.reason_code}, whose CHECK lists the same values.
 *
 * <p>The causes a cup and packaging warehouse actually sees, so a manager can tell a handling problem (broken,
 * crushed) from a storage one (wet, mould) or a supplier one (misprinted) when deciding who pays.</p>
 *
 * <p>Expiry is deliberately not a reason: expired stock is the nightly sweep's (BR-STK-004, status EXPIRED) and is
 * written off with the adjustment reason EXPIRED, never reported as damage.</p>
 */
public enum DamageReason {
    /** Broken or cracked. */
    BROKEN,
    /** Crushed, dented or deformed. */
    DEFORMED,
    /** Wet, damp or mouldy. */
    WET_OR_MOULDY,
    /** Dirty, stained or misprinted. */
    DIRTY_OR_MISPRINTED,
    /** Needs a note: "other" with no explanation is not a reason. */
    OTHER;

    public boolean needsNote() {
        return this == OTHER;
    }
}

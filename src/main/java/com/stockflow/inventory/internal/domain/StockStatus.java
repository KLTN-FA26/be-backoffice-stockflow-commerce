package com.stockflow.inventory.internal.domain;

/**
 * Condition of a stock record — a business rule expressed as a type rather than a label.
 *
 * <p>Only {@link #AVAILABLE} stock may be reserved or sold, which is what keeps goods still
 * awaiting QC (BRD 3.3.4) out of customer orders.</p>
 *
 * <p>State machine, from {@code docs/business-design/03-state-machines.md}:</p>
 * <pre>
 *   QUARANTINE ──qcPassed──▶ AVAILABLE ──damageFound──▶ DAMAGED
 *        │                       │
 *        └──qcFailed──▶ DAMAGED  └──expiryReached──▶ EXPIRED
 * </pre>
 */
public enum StockStatus {

    /** Passed QC, in a pickable location, counted towards available-to-promise. */
    AVAILABLE,

    /** Awaiting or failed QC. Physically present, commercially invisible. */
    QUARANTINE,

    /** Damaged in handling; awaiting a write-off decision. */
    DAMAGED,

    /** Past its expiry date. Kept as a row so the loss stays traceable, never sold. */
    EXPIRED;

    /**
     * Exhaustive switch expression rather than {@code this == AVAILABLE}: when someone adds a
     * fifth status the compiler stops here and makes them decide, instead of silently defaulting
     * the new status to non-sellable — or, worse, to sellable.
     */
    public boolean isReservable() {
        return switch (this) {
            case AVAILABLE -> true;
            case QUARANTINE, DAMAGED, EXPIRED -> false;
        };
    }

    /** Whether stock in this status still counts as company property on the balance sheet. */
    public boolean isOnBalanceSheet() {
        return switch (this) {
            case AVAILABLE, QUARANTINE, DAMAGED -> true;
            case EXPIRED -> false;
        };
    }
}

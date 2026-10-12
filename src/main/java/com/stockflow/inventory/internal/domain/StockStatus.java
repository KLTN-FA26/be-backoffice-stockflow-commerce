package com.stockflow.inventory.internal.domain;

/**
 * Condition of a stock record — a business rule expressed as a type rather than a label.
 *
 * <p>Only {@link #AVAILABLE} stock may be reserved or sold, which is what keeps goods still
 * awaiting QC (BRD 3.3.4) out of customer orders.</p>
 *
 * <p>State machine, from the receiving docs (KLTN-FA26/docs 03 Receipt, 05 Putaway):</p>
 * <pre>
 *   INBOUND ──putaway (no QC, or QC accepted)──▶ AVAILABLE ──damageFound──▶ DAMAGED
 *      │                                             │
 *      ├──QC: quarantine──▶ QUARANTINE ──accepted──▶ (putaway) AVAILABLE
 *      │                        └──rejected──▶ BLOCKED   └──expiryReached──▶ EXPIRED
 *      └──QC: rejected──▶ BLOCKED ──returned to supplier──▶ (leaves stock)
 *
 *   AVAILABLE ──issued to the print shop, held──▶ IN_PRODUCTION ──finished──▶ AVAILABLE (PACKING, held)
 *                                                      ├──unprinted, returned──▶ AVAILABLE
 *                                                      └──spoilt, out of the hold──approved write-off──▶ (leaves)
 * </pre>
 *
 * <p>{@link StockItem#receive} still creates {@link #QUARANTINE} stock; the receipt flow that
 * starts goods as {@link #INBOUND} arrives with SCRUM-435.</p>
 */
public enum StockStatus {

    /** Received, not yet put away: in a RECEIVING or QUALITY_CONTROL area (docs 03). Not sellable. */
    INBOUND,

    /** Passed QC, in a pickable location, counted towards available-to-promise. */
    AVAILABLE,

    /** Put on hold by QC, waiting for a second decision. Physically present, commercially invisible. */
    QUARANTINE,

    /** Rejected by QC, waiting to go back to the supplier. Never sold, never put away. */
    BLOCKED,

    /** Damaged in handling; awaiting a write-off decision. */
    DAMAGED,

    /** Past its expiry date. Kept as a row so the loss stays traceable, never sold. */
    EXPIRED,

    /**
     * Issued to the print shop (SCRUM-397): in a PRODUCTION area, held for its order or production order.
     * Never sellable, whatever happens to the hold, and the hold itself is closed only by production: an order
     * cancelled after its blanks were issued does not release it, so printed cups never come back as blanks
     * another order can buy and production can still tell printed from unprinted. The goods leave this status
     * only by an explicit step: finished goods to PACKING, unprinted blanks back to storage, spoilt units
     * written off by an approved adjustment.
     */
    IN_PRODUCTION;

    /**
     * Exhaustive switch expression rather than {@code this == AVAILABLE}: when someone adds a
     * fifth status the compiler stops here and makes them decide, instead of silently defaulting
     * the new status to non-sellable — or, worse, to sellable.
     */
    public boolean isReservable() {
        return switch (this) {
            case AVAILABLE -> true;
            case INBOUND, QUARANTINE, BLOCKED, DAMAGED, EXPIRED, IN_PRODUCTION -> false;
        };
    }

    /** Whether stock in this status still counts as company property on the balance sheet. */
    public boolean isOnBalanceSheet() {
        return switch (this) {
            case INBOUND, AVAILABLE, QUARANTINE, BLOCKED, DAMAGED, IN_PRODUCTION -> true;
            case EXPIRED -> false;
        };
    }
}

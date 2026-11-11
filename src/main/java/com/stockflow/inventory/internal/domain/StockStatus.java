package com.stockflow.inventory.internal.domain;

/**
 * Condition of a stock record — a business rule expressed as a type rather than a label.
 *
 * <p>Only {@link #AVAILABLE} stock may be reserved or sold, which is what keeps goods not yet put away out of customer
 * orders.</p>
 *
 * <p>State machine, from the receiving docs (KLTN-FA26/docs 03 Receipt, 05 Putaway, 20 Inventory control). There is no
 * quality check at the warehouse (03 BR-07); QUARANTINE and BLOCKED rows written by receiving QC before it was removed
 * stay as they are:</p>
 * <pre>
 *   INBOUND ──putaway──▶ AVAILABLE ──damageFound──▶ DAMAGED
 *                            │
 *   returned goods: QUARANTINE ──passed──▶ (putaway) AVAILABLE
 *                     └──condemned──▶ BLOCKED
 * </pre>
 *
 * <p>{@link #EXPIRED} is no longer reached: cups and packaging have no expiry date and the expiry sweep is gone
 * (kltn-docs 01 BR-17, 20 BR-13; team plan 5-1).</p>
 *
 * <p>{@link StockItem#receive} still creates {@link #QUARANTINE} stock; the receipt flow that
 * starts goods as {@link #INBOUND} arrives with SCRUM-435.</p>
 */
public enum StockStatus {

    /** Received, not yet put away: in a RECEIVING area (docs 03). Not sellable. */
    INBOUND,

    /** Put away, in a pickable location, counted towards available-to-promise. */
    AVAILABLE,

    /** On hold apart from good stock (a return, or legacy receiving QC), waiting for a decision. Physically present,
     *  commercially invisible. */
    QUARANTINE,

    /** Condemned (a return, or legacy receiving QC), waiting to leave stock. Never sold, never put away. */
    BLOCKED,

    /** Damaged in handling; awaiting a write-off decision. */
    DAMAGED,

    /**
     * Legacy: past its expiry date, set by the nightly expiry sweep removed in team plan 5-1. Nothing enters it any more
     * ({@code tg_stock_item_no_new_expired}); kept because rows and ledger lines recorded before carry it. Never
     * sellable; an approved adjustment writes what is left off.
     */
    EXPIRED,

    /**
     * Legacy: blanks issued to the in-house print shop or a subcontractor (SCRUM-397/441). Nothing enters it since
     * in-house production was removed (kltn-docs 07/11; V20261108000100 released the holds still on such rows). Kept
     * because stock rows and ledger lines recorded before carry it ({@code ck_stock_item_status}). Never sellable; an
     * approved adjustment writes what is left off.
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

package com.stockflow.inventory.internal.domain;

/**
 * Why a hold was given up.
 *
 * <p>Deliberately a separate enum from {@code com.stockflow.contracts.ReleaseReason} even though
 * the values currently match. This one may gain values freely; adding a value to the contract
 * enum is a breaking change for every listener. {@link #toContract()} is the single translation
 * point, and its exhaustive switch fails the build if a new internal value has no contract
 * counterpart — so the two can never drift apart unnoticed.</p>
 */
public enum ReleaseReason {

    ORDER_CANCELLED,
    PAYMENT_FAILED,
    RESERVATION_EXPIRED,
    MANUAL_OVERRIDE,
    /** A production order no longer needs the blanks it held (cancelled before using them). */
    PRODUCTION_CANCELLED,
    /** Spoilt in the print shop: the units are written off by an approved SCRAP adjustment (SCRUM-398). */
    SCRAPPED,
    /** Issued to the print shop and not printed: back to storage, free (SCRUM-400/401). */
    RETURNED_FROM_PRODUCTION,
    /** Part of a production order went to a subcontractor who sources its own blanks (SCRUM-441, G3). */
    SUBCONTRACTED,
    /** A picker found fewer units at the bin than were allocated there; the units were allocated elsewhere (X-SHORT). */
    SHORT_PICKED,
    /** Picking of a transfer order was cancelled before its goods left: its hold is free again (SCRUM-287 PR T). */
    TRANSFER_CANCELLED,
    /**
     * A picker found the held units damaged (SCRUM-277): they went to quarantine, and the order holds the same
     * quantity again elsewhere, or as an impaired allocation on the damaged units until it is reallocated.
     */
    DAMAGED;

    public com.stockflow.contracts.ReleaseReason toContract() {
        return switch (this) {
            case ORDER_CANCELLED -> com.stockflow.contracts.ReleaseReason.ORDER_CANCELLED;
            case PAYMENT_FAILED -> com.stockflow.contracts.ReleaseReason.PAYMENT_FAILED;
            case RESERVATION_EXPIRED -> com.stockflow.contracts.ReleaseReason.RESERVATION_EXPIRED;
            case MANUAL_OVERRIDE -> com.stockflow.contracts.ReleaseReason.MANUAL_OVERRIDE;
            // A production hold announces nothing to order (StockItem#releaseReservation). SCRAPPED reaches it only
            // for a cancelled order's printed goods (BR-PRD-10), which the order no longer acts on.
            // SHORT_PICKED: the order keeps its stock, on another bin; nothing for it to act on. TRANSFER_CANCELLED: a
            // transfer's hold, no order's. DAMAGED: the order keeps its units elsewhere or impaired, and learns of it
            // in the picker's own transaction (or from StockDamageReported); nothing to act on here either.
            case PRODUCTION_CANCELLED, SCRAPPED, RETURNED_FROM_PRODUCTION, SUBCONTRACTED, SHORT_PICKED,
                 TRANSFER_CANCELLED, DAMAGED ->
                    com.stockflow.contracts.ReleaseReason.MANUAL_OVERRIDE;
        };
    }
}

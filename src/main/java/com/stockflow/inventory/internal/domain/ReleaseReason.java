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
    /** Picking of a transfer order was cancelled before its goods left: its allocation is free again (PR T). */
    TRANSFER_CANCELLED;

    public com.stockflow.contracts.ReleaseReason toContract() {
        return switch (this) {
            case ORDER_CANCELLED -> com.stockflow.contracts.ReleaseReason.ORDER_CANCELLED;
            case PAYMENT_FAILED -> com.stockflow.contracts.ReleaseReason.PAYMENT_FAILED;
            case RESERVATION_EXPIRED -> com.stockflow.contracts.ReleaseReason.RESERVATION_EXPIRED;
            case MANUAL_OVERRIDE -> com.stockflow.contracts.ReleaseReason.MANUAL_OVERRIDE;
            // A production hold announces nothing to order (StockItem#releaseReservation). SCRAPPED reaches it only
            // for a cancelled order's printed goods (BR-PRD-10), which the order no longer acts on.
            case PRODUCTION_CANCELLED, SCRAPPED, RETURNED_FROM_PRODUCTION, SUBCONTRACTED, TRANSFER_CANCELLED ->
                    com.stockflow.contracts.ReleaseReason.MANUAL_OVERRIDE;
        };
    }
}

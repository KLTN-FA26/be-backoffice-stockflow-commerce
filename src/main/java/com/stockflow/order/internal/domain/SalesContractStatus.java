package com.stockflow.order.internal.domain;

/**
 * A sales contract's status (kltn-docs 14 §5.2): DRAFT → SENT → SIGNED → COMPLETED; DRAFT and SENT → CANCELLED. The
 * database checks the same edges ({@code tg_sales_contract_transition}).
 */
public enum SalesContractStatus {

    /** Sales is drawing it up for a draft order; it changes freely. */
    DRAFT,
    /** Sent to the customer, waiting to be signed; its content is locked. */
    SENT,
    /** Signed outside the system, the signed file recorded; immutable. */
    SIGNED,
    /** The order is complete and every installment collected (wired in 4B-8). */
    COMPLETED,
    /** Cancelled before it was signed, with a reason; a new one may be drawn up for the order. */
    CANCELLED;

    /** Whether the order is bound by it: every status but CANCELLED (BR-12: one live contract per order). */
    public boolean live() {
        return this != CANCELLED;
    }

    public boolean canTransitionTo(SalesContractStatus target) {
        return switch (this) {
            case DRAFT -> target == SENT || target == CANCELLED;
            case SENT -> target == SIGNED || target == CANCELLED;
            case SIGNED -> target == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}

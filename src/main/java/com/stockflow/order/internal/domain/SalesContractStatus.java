package com.stockflow.order.internal.domain;

/**
 * A sales contract's status (kltn-docs 14 §5.2): DRAFT → SENT → CUSTOMER_SIGNED → SIGNED → COMPLETED or LIQUIDATED;
 * SENT → SIGNED directly when staff record a paper signed by both; SENT → DRAFT when the customer asks for changes (the
 * next revision); DRAFT, SENT and CUSTOMER_SIGNED → CANCELLED. The database checks the same edges
 * ({@code tg_sales_contract_transition}), and that COMPLETED is reached only when goods of the order were delivered and
 * LIQUIDATED only when none were (WF-05 Q-6, L-I4).
 */
public enum SalesContractStatus {

    /** Sales is drawing it up for a draft order; it changes freely. */
    DRAFT,
    /** Sent to the customer, waiting to be signed; its content is locked. */
    SENT,
    /**
     * The customer signed on the portal (or sent a file signed by them) and the company has not yet: content locked like
     * SENT's. The order is not bound - it still waits in PENDING_CONTRACT - until the company signs (QĐ-1).
     */
    CUSTOMER_SIGNED,
    /** Signed by both sides - by the company's signer after the customer, or recorded from a signed file; immutable. */
    SIGNED,
    /** The order is complete and every installment collected (wired in 4B-8). */
    COMPLETED,
    /** Cancelled before the company signed it, with a reason; a new one may be drawn up for the order. */
    CANCELLED,
    /**
     * WF-05 Q-6 ("Thanh lý"): signed, and its order ended with nothing delivered - every shipment brought back by the
     * carrier or lost, or the order cancelled after signing - once its money is settled. Final, like COMPLETED, but a
     * report can tell a contract performed from one liquidated.
     */
    LIQUIDATED;

    /** Whether the contract is out with the customer, its content locked, and not yet in force: SENT or CUSTOMER_SIGNED. */
    public boolean awaitingSignatures() {
        return this == SENT || this == CUSTOMER_SIGNED;
    }

    /** Whether the order is bound by it: every status but CANCELLED (BR-12: one live contract per order). */
    public boolean live() {
        return this != CANCELLED;
    }

    public boolean canTransitionTo(SalesContractStatus target) {
        return switch (this) {
            case DRAFT -> target == SENT || target == CANCELLED;
            // DRAFT again: the customer asked for changes (SalesContract#requestChanges); SIGNED: a paper signed by both recorded.
            case SENT -> target == CUSTOMER_SIGNED || target == SIGNED || target == CANCELLED || target == DRAFT;
            case CUSTOMER_SIGNED -> target == SIGNED || target == CANCELLED;
            case SIGNED -> target == COMPLETED || target == LIQUIDATED;
            case COMPLETED, CANCELLED, LIQUIDATED -> false;
        };
    }
}

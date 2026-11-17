package com.stockflow.order.internal.domain;

/**
 * The life of a return request (RMA; kltn-docs 17 §4.10, team plan 4D-3). Mapped {@code EnumType.STRING};
 * {@code ck_return_request_status} holds the same names.
 *
 * <pre>
 *   REQUESTED ─approve─▶ AWAITING_RETURN ─return receipt confirmed─▶ RECEIVED ─refund proposed─▶ SETTLEMENT_PENDING
 *       ├─reject─▶ REJECTED        └─deadline passed─▶ EXPIRED                      ▲               │
 *       └─cancel─▶ CANCELLED ◀─cancel─ AWAITING_RETURN                             └──sent back────┤
 *                                                                          approved by another ─▶ RESOLVED
 *   EXPIRED, CANCELLED (after approval) ─a return receipt opened before brings the goods anyway─▶ RECEIVED
 * </pre>
 *
 * <p>Sales approve the claim on its photos, and the goods always come back (17 BR-18): the warehouse receives them on a
 * return receipt, carton by carton, into quarantine (03 §4.2) - that receipt, confirmed, is what moves the return to
 * RECEIVED. There is no QC of returned goods any more: a warehouse manager releases or writes off each carton in
 * quarantine (20 §4.8), which is a decision about stock, not about the return. What the return still decides is the
 * money: Sales propose the refund, a second person approves it, and payment settles it against the order's
 * installments (15 §4.5). An exchange is a new quote (17 §4.10 step 41); nothing is replaced or credited.</p>
 */
public enum ReturnStatus {

    /** Opened by the customer or by Sales for them; Sales review it. */
    REQUESTED,

    /** Approved (the RMA); the customer sends the approved goods back to the order's warehouse before the deadline. */
    AWAITING_RETURN,

    /** The goods came back on a return receipt, into quarantine; Sales propose the refund. */
    RECEIVED,

    /**
     * Historical (SCRUM-260): QC had inspected the goods. No return reaches it since team plan 4D-3, which has no QC;
     * kept because the status history still names it.
     */
    INSPECTED,

    /** A refund waits for a second person (four eyes). */
    SETTLEMENT_PENDING,

    /** The refund was approved and settled against the order's installments. */
    RESOLVED,

    /** Refused at review. */
    REJECTED,

    /** Withdrawn before the goods came back. */
    CANCELLED,

    /** The approved goods did not arrive by the deadline. */
    EXPIRED;

    /** Still being worked on: it holds its units and keeps the order from completing (BR-ORD-005). */
    public boolean isOpen() {
        return switch (this) {
            case REQUESTED, AWAITING_RETURN, RECEIVED, SETTLEMENT_PENDING -> true;
            case INSPECTED, RESOLVED, REJECTED, CANCELLED, EXPIRED -> false;
        };
    }

    /** Whether the goods of the return are back in the warehouse: a return receipt brought them. */
    public boolean goodsBack() {
        return switch (this) {
            case RECEIVED, SETTLEMENT_PENDING, RESOLVED -> true;
            case REQUESTED, AWAITING_RETURN, INSPECTED, REJECTED, CANCELLED, EXPIRED -> false;
        };
    }

    /**
     * The transition table, as one exhaustive switch: adding a status forces every branch to be revisited. The
     * aggregate's methods are the only callers; each also checks what the step needs.
     */
    public boolean canTransitionTo(ReturnStatus target) {
        return switch (this) {
            case REQUESTED -> target == AWAITING_RETURN || target == REJECTED || target == CANCELLED;
            case AWAITING_RETURN -> target == RECEIVED || target == CANCELLED || target == EXPIRED;
            // Goods are physically here: a return receipt opened while the return awaited them brings them in whatever
            // happened to the return meanwhile. The aggregate checks a CANCELLED one was approved.
            case EXPIRED, CANCELLED -> target == RECEIVED;
            case RECEIVED -> target == SETTLEMENT_PENDING;
            case SETTLEMENT_PENDING -> target == RESOLVED || target == RECEIVED;
            case INSPECTED, RESOLVED, REJECTED -> false;
        };
    }
}

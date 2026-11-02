package com.stockflow.order.internal.domain;

/**
 * The life of a return request (RMA, 03-state-machines §12; SCRUM-243 / SCRUM-260). Mapped {@code EnumType.STRING};
 * {@code ck_return_request_status} holds the same names.
 *
 * <pre>
 *   REQUESTED ─approve, goods come back─▶ AWAITING_RETURN ─parcel counted in─▶ RECEIVED ─QC─▶ INSPECTED
 *       │       └approve, goods stay (photos)────────────────────────────────────────────────▶ INSPECTED
 *       ├─reject─▶ REJECTED              AWAITING_RETURN ─deadline passed─▶ EXPIRED
 *       └─cancel─▶ CANCELLED ◀─cancel─ AWAITING_RETURN          RECEIVED ─QC finds no defect─▶ REJECTED
 *   RECEIVED ─another parcel─▶ RECEIVED (no transition: the count grows)
 *   EXPIRED, CANCELLED (after approval) ─the goods come anyway─▶ RECEIVED
 *   INSPECTED ─resolution proposed─▶ SETTLEMENT_PENDING ─approved by another person─▶ RESOLVED
 *                                                       └─sent back─▶ INSPECTED
 * </pre>
 *
 * <p>03 §12 drew APPROVED and AWAITING_RETURN as two states joined by "return instructions sent" by the system. With no
 * carrier integration (docs 09) the instructions are the approval itself - the RMA number to write on the parcel and
 * the deadline - so the two are one state. Its REFUNDED / EXCHANGED ends are one RESOLVED: a resolution may replace
 * some units and credit others, and what it did is on the request.</p>
 */
public enum ReturnStatus {

    /** Opened by the customer or by Sales for them; Sales review it. */
    REQUESTED,

    /** Approved; the customer sends the approved units back before the deadline. */
    AWAITING_RETURN,

    /** The parcel was counted into a quarantine area; QC inspects it. */
    RECEIVED,

    /** Every unit is accepted (the claim holds) or rejected; how the accepted ones are made good is next. */
    INSPECTED,

    /** A resolution - money, replacement goods, or both - waits for a second person (four eyes). */
    SETTLEMENT_PENDING,

    /** Every accepted unit was replaced or credited. */
    RESOLVED,

    /** Refused at review, or nothing received was defective. */
    REJECTED,

    /** Withdrawn before the goods came back. */
    CANCELLED,

    /** The approved goods did not arrive by the deadline; goods that come afterwards bring it back to RECEIVED. */
    EXPIRED;

    /** Still being worked on: it holds its units and keeps the order from completing (BR-ORD-005). */
    public boolean isOpen() {
        return switch (this) {
            case REQUESTED, AWAITING_RETURN, RECEIVED, INSPECTED, SETTLEMENT_PENDING -> true;
            case RESOLVED, REJECTED, CANCELLED, EXPIRED -> false;
        };
    }

    /**
     * The transition table, as one exhaustive switch: adding a status forces every branch to be revisited. The
     * aggregate's methods are the only callers; each also checks what the step needs.
     */
    public boolean canTransitionTo(ReturnStatus target) {
        return switch (this) {
            // INSPECTED: approved with the goods staying with the customer - the photos were the inspection.
            case REQUESTED -> target == AWAITING_RETURN || target == INSPECTED || target == REJECTED
                    || target == CANCELLED;
            case AWAITING_RETURN -> target == RECEIVED || target == CANCELLED || target == EXPIRED;
            // Goods are physically here, so they are counted in whatever the return's state was: approved goods that
            // came after the deadline, or after the customer withdrew. The aggregate checks a CANCELLED one was approved.
            case EXPIRED, CANCELLED -> target == RECEIVED;
            // REJECTED: QC found nothing received defective.
            case RECEIVED -> target == INSPECTED || target == REJECTED;
            // Every resolution is approved by a second person: replacement goods are worth what money is.
            case INSPECTED -> target == SETTLEMENT_PENDING;
            case SETTLEMENT_PENDING -> target == RESOLVED || target == INSPECTED;
            case RESOLVED, REJECTED -> false;
        };
    }
}

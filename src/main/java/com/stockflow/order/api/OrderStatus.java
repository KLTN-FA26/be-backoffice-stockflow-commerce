package com.stockflow.order.api;

/**
 * Order lifecycle (kltn-docs 17 §5, {@code docs/business-design/03-state-machines.md}).
 *
 * <p>Public because {@code OrderSummary} carries it and other modules branch on it. The
 * transitions stay private to the module — {@link #canTransitionTo} is exposed so a caller can
 * ask, but only {@code order} may actually move a row.</p>
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_PAYMENT ──paid in full / deposit in──▶ CONFIRMED ──release, stock only──▶ READY_TO_FULFILL
 *     │                     │                                           │                                  │
 *     │                     ├──payment failed / hold expired──▶ CANCELLED                                  │
 *     └──cancel────────────▶ CANCELLED ◀──cancel──                      └─release, print──▶ IN_PRODUCTION  │
 *                                                                        IN_PRODUCTION ──every LSX done──▶ READY_TO_FULFILL
 *                                                                                                          │
 *                       CONFIRMED (stock only, legacy) / READY_TO_FULFILL ──fulfilment──▶ IN_FULFILMENT ──▶ SHIPPED
 *                                                                          ──▶ DELIVERED ──▶ COMPLETED / RETURNED
 * </pre>
 *
 * <p>{@code CONFIRMED} is kltn-docs 17's "Confirmed": the order may go ahead — paid in full,
 * deposit received, or (SCRUM-427) on credit within the limit. How much has actually been paid is
 * not a status: the order tracks it as an amount, and the payment status (unpaid, partially paid,
 * paid) is derived from it (kltn-docs 15 §5.2). There was a {@code PAID} status until SCRUM-460;
 * rows in it became {@code CONFIRMED}.</p>
 *
 * <p>A credit order skips PENDING_PAYMENT (kltn-docs 17 §5, 15 §4.3; SCRUM-427): within the
 * customer's limit it is CONFIRMED when placed, over it ON_HOLD until whoever approves credit
 * confirms it or refuses — then it is cancelled, or switched to prepaid or deposit and waits for
 * payment like any other order.</p>
 *
 * <p>Release (SCRUM-423, 03 §9) is the Order Coordinator's step: it fixes the warehouse and, for an
 * order with print lines, starts production. An order with print lines reaches fulfilment only through
 * READY_TO_FULFILL, once production has delivered every line (BR-PRD-06).</p>
 */
public enum OrderStatus {

    /** Cart contents, not yet an order. No stock is held. */
    DRAFT,

    /** Submitted, stock reserved, waiting for the prepayment or the deposit. */
    PENDING_PAYMENT,

    /**
     * Cleared to go ahead: paid in full (prepaid) or deposit received (kltn-docs 15 BR-02). Its
     * stock holds are pinned; the coordinator may release it.
     */
    CONFIRMED,

    /** Released with lines to print; production orders are running (SCRUM-423). */
    IN_PRODUCTION,

    /** Released, and everything it needs is in stock: fulfilment may take it on. */
    READY_TO_FULFILL,

    /** Handed to fulfilment: picking, packing, and for made-to-order items, production. */
    IN_FULFILMENT,
    ON_HOLD,

    SHIPPED,
    DELIVERED,

    /** Closed successfully; the return window has passed. */
    COMPLETED,

    CANCELLED,
    RETURNED;

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, CANCELLED, RETURNED -> true;
            case DRAFT, PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, IN_FULFILMENT, ON_HOLD,
                 SHIPPED, DELIVERED -> false;
        };
    }

    /** True while the order still holds stock that must be released if it is cancelled. */
    public boolean holdsStock() {
        return switch (this) {
            case PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, IN_FULFILMENT, ON_HOLD -> true;
            case DRAFT, SHIPPED, DELIVERED, COMPLETED, CANCELLED, RETURNED -> false;
        };
    }

    /**
     * Whether a customer may cancel on their own (SCRUM-460, 03 §9): before anything has been
     * released to the warehouse or production. Later, cancelling stops work in progress and may
     * cost a fee, so the customer asks and Sales or the coordinator decides (kltn-docs 17 §2, §4.4).
     */
    public boolean customerMayCancelDirectly() {
        return this == DRAFT || this == PENDING_PAYMENT || this == CONFIRMED;
    }

    /**
     * The transition table, as one exhaustive switch.
     *
     * <p>Written here rather than as scattered {@code if (status != X) throw} checks so that the
     * whole machine is readable in one place, and so that adding a status forces every branch to
     * be revisited. BR-031: cancellation is refused from SHIPPED onwards — at that point the goods
     * are with the carrier and the correct process is a return, not a cancellation.</p>
     */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            // CONFIRMED / ON_HOLD: a credit order, within or over its limit (SCRUM-427).
            case DRAFT -> target == PENDING_PAYMENT || target == CONFIRMED || target == ON_HOLD || target == CANCELLED;
            // kltn-docs 15 BR-02: a deposit order is confirmed once its deposit is in; nothing leaves
            // PENDING_PAYMENT for production or the warehouse directly.
            case PENDING_PAYMENT -> target == CONFIRMED || target == CANCELLED;
            // IN_FULFILMENT: fulfilment may still take a stock-only order that was never released.
            case CONFIRMED -> target == IN_PRODUCTION || target == READY_TO_FULFILL || target == IN_FULFILMENT
                    || target == ON_HOLD || target == CANCELLED;
            case IN_PRODUCTION -> target == READY_TO_FULFILL || target == ON_HOLD || target == CANCELLED;
            case READY_TO_FULFILL -> target == IN_FULFILMENT || target == ON_HOLD || target == CANCELLED;
            case IN_FULFILMENT -> target == SHIPPED || target == ON_HOLD || target == CANCELLED;
            // CONFIRMED: credit approved; PENDING_PAYMENT: credit refused, switched to prepaid or deposit
            // (kltn-docs 15 §4.3). IN_FULFILMENT: a design hold resolved.
            case ON_HOLD -> target == CONFIRMED || target == PENDING_PAYMENT || target == IN_FULFILMENT
                    || target == CANCELLED;
            case SHIPPED -> target == DELIVERED;
            case DELIVERED -> target == COMPLETED || target == RETURNED;
            case COMPLETED, CANCELLED, RETURNED -> false;
        };
    }
}

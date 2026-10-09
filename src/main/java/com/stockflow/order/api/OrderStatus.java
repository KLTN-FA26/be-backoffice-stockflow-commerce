package com.stockflow.order.api;

/**
 * Order lifecycle (BRD 3.17, {@code docs/business-design/03-state-machines.md}).
 *
 * <p>Public because {@code OrderSummary} carries it and other modules branch on it. The
 * transitions stay private to the module — {@link #canTransitionTo} is exposed so a caller can
 * ask, but only {@code order} may actually move a row.</p>
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_PAYMENT ──paymentCaptured──▶ PAID ──release, stock only──▶ READY_TO_FULFILL
 *     │                     │   └──release, deposit in (BR-PRD-09)──┐      │                    │
 *     │                     ├──paymentFailed──▶ CANCELLED            ▼      └─release, print──▶ IN_PRODUCTION
 *     └──cancel────────────▶ CANCELLED ◀──cancel──           IN_PRODUCTION ──every LSX done──▶ READY_TO_FULFILL
 *                                                                                                   │
 *                         PAID (legacy, stock only) / READY_TO_FULFILL ──fulfilment──▶ IN_FULFILMENT ──▶ SHIPPED
 *                                                                          ──▶ DELIVERED ──▶ COMPLETED / RETURNED
 * </pre>
 *
 * <p>Release (SCRUM-423, 03 §9) is the Order Coordinator's step: it fixes the warehouse and, for an
 * order with print lines, starts production. An order with print lines reaches fulfilment only through
 * READY_TO_FULFILL, once production has delivered every line (BR-PRD-06).</p>
 */
public enum OrderStatus {

    /** Cart contents, not yet an order. No stock is held. */
    DRAFT,

    /** Submitted, stock reserved, waiting for the customer to pay. */
    PENDING_PAYMENT,

    /** Payment captured. The reservation becomes an allocation the warehouse may pick. */
    PAID,

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
            case DRAFT, PENDING_PAYMENT, PAID, IN_PRODUCTION, READY_TO_FULFILL, IN_FULFILMENT, ON_HOLD,
                 SHIPPED, DELIVERED -> false;
        };
    }

    /** True while the order still holds stock that must be released if it is cancelled. */
    public boolean holdsStock() {
        return switch (this) {
            case PENDING_PAYMENT, PAID, IN_PRODUCTION, READY_TO_FULFILL, IN_FULFILMENT, ON_HOLD -> true;
            case DRAFT, SHIPPED, DELIVERED, COMPLETED, CANCELLED, RETURNED -> false;
        };
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
            case DRAFT -> target == PENDING_PAYMENT || target == CANCELLED;
            // PENDING_PAYMENT -> IN_PRODUCTION only for a deposit order whose deposit is in (BR-PRD-09);
            // the aggregate checks the term, the table cannot.
            case PENDING_PAYMENT -> target == PAID || target == IN_PRODUCTION || target == CANCELLED;
            case PAID -> target == IN_PRODUCTION || target == READY_TO_FULFILL || target == IN_FULFILMENT
                    || target == ON_HOLD || target == CANCELLED;
            case IN_PRODUCTION -> target == READY_TO_FULFILL || target == ON_HOLD || target == CANCELLED;
            case READY_TO_FULFILL -> target == IN_FULFILMENT || target == ON_HOLD || target == CANCELLED;
            case IN_FULFILMENT -> target == SHIPPED || target == ON_HOLD || target == CANCELLED;
            case ON_HOLD -> target == IN_FULFILMENT || target == CANCELLED;
            case SHIPPED -> target == DELIVERED;
            case DELIVERED -> target == COMPLETED || target == RETURNED;
            case COMPLETED, CANCELLED, RETURNED -> false;
        };
    }
}

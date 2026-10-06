package com.stockflow.order.api;

/**
 * Order lifecycle (BRD 3.17, {@code docs/business-design/03-state-machines.md}).
 *
 * <p>Public because {@code OrderSummary} carries it and other modules branch on it. The
 * transitions stay private to the module — {@link #canTransitionTo} is exposed so a caller can
 * ask, but only {@code order} may actually move a row.</p>
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_PAYMENT ──paymentCaptured──▶ PAID ──release──▶ IN_FULFILMENT
 *     │                     │                                                      │
 *     │                     ├──paymentFailed──▶ CANCELLED                          ▼
 *     └──cancel────────────▶ CANCELLED ◀──cancel───────────────────────────    SHIPPED ──▶ DELIVERED
 *                                                                                          │
 *                                                                                    COMPLETED / RETURNED
 * </pre>
 */
public enum OrderStatus {

    /** Cart contents, not yet an order. No stock is held. */
    DRAFT,

    /** Submitted, stock reserved, waiting for the customer to pay. */
    PENDING_PAYMENT,

    /** Payment captured. The reservation becomes an allocation the warehouse may pick. */
    PAID,

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
            case DRAFT, PENDING_PAYMENT, PAID, IN_FULFILMENT, ON_HOLD, SHIPPED, DELIVERED -> false;
        };
    }

    /** True while the order still holds stock that must be released if it is cancelled. */
    public boolean holdsStock() {
        return switch (this) {
            case PENDING_PAYMENT, PAID, IN_FULFILMENT, ON_HOLD -> true;
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
            case PENDING_PAYMENT -> target == PAID || target == CANCELLED;
            case PAID -> target == IN_FULFILMENT || target == ON_HOLD || target == CANCELLED;
            case IN_FULFILMENT -> target == SHIPPED || target == ON_HOLD || target == CANCELLED;
            case ON_HOLD -> target == IN_FULFILMENT || target == CANCELLED;
            case SHIPPED -> target == DELIVERED;
            case DELIVERED -> target == COMPLETED || target == RETURNED;
            case COMPLETED, CANCELLED, RETURNED -> false;
        };
    }
}

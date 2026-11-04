package com.stockflow.order.api;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Order lifecycle (kltn-docs 17 §5, {@code docs/business-design/03-state-machines.md} §9; SCRUM-239/246/247).
 *
 * <p>Public because {@code OrderSummary} carries it and other modules branch on it. The transitions stay private to
 * the module — {@link #canTransitionTo} is exposed so a caller can ask, but only {@code order} may actually move a
 * row. The same table is written a second time in the database ({@code ordering.order_status_transition}, enforced by
 * the trigger {@code tg_order_status_transition}); {@code OrderLifecycleIntegrationTest#triggerRefusesJumps} and
 * {@code OrderLifecycleMigrationUpgradeTest} check the two are equal.</p>
 *
 * <pre>
 *   DRAFT ─▶ PENDING_PAYMENT ─paid / deposit in─▶ CONFIRMED ─release─▶ IN_PRODUCTION ─every LSX done─▶ READY_TO_FULFILL
 *     │  └─▶ CONFIRMED / ON_HOLD (credit, within / over the limit)    └─release, stock only──────────▶ READY_TO_FULFILL
 *                                                                                                          │
 *   READY_TO_FULFILL ─▶ PICKING ─▶ PACKED ─handover─▶ SHIPPED ─▶ DELIVERED ─▶ COMPLETED ─▶ CLOSED
 *          └──── print only ────▶ PACKED      │                   └─▶ DELIVERY_FAILED ─▶ DELIVERED / RETURNED
 *                     PICKING / PACKED ─part handed over─▶ PARTIALLY_FULFILLED ─rest packed─▶ PACKED
 *   IN_PRODUCTION / READY_TO_FULFILL ─stock handed over before the prints (PACK-EARLY)─▶ PARTIALLY_FULFILLED
 *   DELIVERED ─▶ RETURN_REQUESTED ─▶ RETURNED ─▶ REFUNDED / PARTIALLY_REFUNDED ─▶ CLOSED
 * </pre>
 *
 * <p><b>Split shipments</b> (decision G4-Q3, 2026-10-19). {@code PARTIALLY_FULFILLED} is kltn-docs 17's "Partially
 * Fulfilled — giao một phần, phần còn lại backorder" and 09's "đơn ở trạng thái giao một phần": some of the order's goods
 * went to the carrier and some did not reach the customer — never shipped yet, or came back. It is left when the rest is
 * packed ({@code PACKED}), handed over ({@code SHIPPED}), or closed by staff, after which the order shows what its
 * shipments say ({@code SHIPPED}, {@code DELIVERY_FAILED}, {@code DELIVERED}, {@code RETURNED}). An order with every
 * unit in a shipment follows its shipments: in transit while one is, {@code DELIVERY_FAILED} once all have an outcome and
 * one failed, {@code DELIVERED} when all arrived; a shipment that comes back while others arrived makes the order
 * partial again.</p>
 *
 * <p><b>Shipping the stock first</b> (PACK-EARLY / PACK-PARTIAL, lead's decisions 2026-10-21; kltn-docs 17 BR-06
 * "trừ khi chính sách split-ship", open question D9). A mixed order whose Sales allowed partial shipments may have its
 * stock lines picked, packed and handed over while its print lines are still printed: IN_PRODUCTION →
 * PARTIALLY_FULFILLED, the order then waiting for production ({@code awaitingProduction}). When production completes
 * the print lines are packed and handed over as the rest of a split order (PARTIALLY_FULFILLED → PACKED → SHIPPED), or
 * staff close them ({@code closeRemainder}). The same from READY_TO_FULFILL when production finished after the stock
 * was packed but before it was handed over. An order that does not allow partial shipments ships whole, as before.</p>
 *
 * <p><b>Hold</b> (17 §5, 09 edge). {@code ON_HOLD} is entered from any status up to {@code PACKED} (and
 * {@code PARTIALLY_FULFILLED}) and left back to the status it was entered from, or to {@code CANCELLED}; a credit hold
 * (an order placed over its limit) is decided through credit approval instead.</p>
 *
 * <p><b>Cancellation</b> stops at the first completed package (decision 2026-10-17, BR-ORD-004): {@code PACKED} and
 * later go through a return. There is no {@code PAYMENT_FAILED}: a B2B bank transfer has no failure to report (G3-Q2).</p>
 */
public enum OrderStatus {

    /** Cart contents, not yet an order. No stock is held. */
    DRAFT,

    /** Submitted, stock reserved, waiting for the prepayment or the deposit. */
    PENDING_PAYMENT,

    /**
     * Cleared to go ahead: paid in full (prepaid), deposit received (kltn-docs 15 BR-02) or credit within the limit.
     * Its stock holds are pinned; the coordinator may release it.
     */
    CONFIRMED,

    /** Released with lines to print; production orders are running (SCRUM-423). */
    IN_PRODUCTION,

    /** Released, and everything it needs is in stock: fulfilment may take it on. */
    READY_TO_FULFILL,

    /** The warehouse is picking it (kltn-docs 07). */
    PICKING,

    /**
     * Part of it went to the carrier and part did not reach the customer: not shipped yet, or came back (split
     * shipment, decision G4-Q3).
     */
    PARTIALLY_FULFILLED,

    /** Everything still to ship is packed and waits at dispatch (kltn-docs 08). */
    PACKED,

    /** Tạm giữ: a credit decision, or an exception the coordinator raised; resolved back where it came from. */
    ON_HOLD,

    /** Every unit is with the carrier or closed, and a shipment is still in transit. */
    SHIPPED,

    /** Every shipment has an outcome and one failed (kltn-docs 09 §4.3). */
    DELIVERY_FAILED,

    /** Every shipment arrived. */
    DELIVERED,

    /** The customer asked to return goods (RMA, SCRUM-243). */
    RETURN_REQUESTED,

    /** The goods came back to the warehouse: returned to origin, or through an RMA. */
    RETURNED,

    /** Refunded in full (or the receivable reduced in full, kltn-docs 15 §4.4). */
    REFUNDED,

    /** Refunded in part. */
    PARTIALLY_REFUNDED,

    /** Closed successfully: delivered, paid, and the return window has passed. */
    COMPLETED,

    CANCELLED,

    /** Closed for good (BR-05: immutable); reached a configured number of days after COMPLETED / REFUNDED. */
    CLOSED;

    public boolean isTerminal() {
        return switch (this) {
            case CANCELLED, CLOSED -> true;
            case DRAFT, PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED,
                 PACKED, ON_HOLD, SHIPPED, DELIVERY_FAILED, DELIVERED, RETURN_REQUESTED, RETURNED, REFUNDED,
                 PARTIALLY_REFUNDED, COMPLETED -> false;
        };
    }

    /** True while the order still holds stock that must be released if it is cancelled. */
    public boolean holdsStock() {
        return switch (this) {
            case PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED, PACKED,
                 ON_HOLD -> true;
            case DRAFT, SHIPPED, DELIVERY_FAILED, DELIVERED, RETURN_REQUESTED, RETURNED, REFUNDED, PARTIALLY_REFUNDED,
                 COMPLETED, CANCELLED, CLOSED -> false;
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
     * Whether a credit order in this status counts against its customer's credit limit on the order's side (kltn-docs
     * 15 BR-03): the part of it not yet billed as a receivable. Written here, as one exhaustive switch, so that a status
     * added later must be classified - a status missing from a hand-written SQL list let a credit order fall out of
     * the exposure and the customer order past the limit (the money risk of the G3-2 audit).
     *
     * <p>Counted while the order may still bill something: from CONFIRMED until every shipment has an outcome. What a
     * delivered shipment billed is the receivables' to count, and the query subtracts it, so a split order is counted
     * once ({@code CreditChecks.undeliveredCreditExposure}). From DELIVERED on, everything delivered was billed and the
     * receivables carry it; a cancelled order owes nothing on its own side (a fee it owes is a receivable too). The
     * credit hold is left out by the query, not here: an order over its limit is not credit given yet.</p>
     */
    public boolean countsTowardCreditExposure() {
        return switch (this) {
            case CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED, PACKED, ON_HOLD, SHIPPED,
                 DELIVERY_FAILED -> true;
            case DRAFT, PENDING_PAYMENT, DELIVERED, RETURN_REQUESTED, RETURNED, REFUNDED, PARTIALLY_REFUNDED,
                 COMPLETED, CANCELLED, CLOSED -> false;
        };
    }

    /** The statuses an order may be put on hold from, and resolved back to (17 §5, 09 edge "sai địa chỉ"). */
    public boolean mayBePutOnHold() {
        return switch (this) {
            case CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED, PACKED -> true;
            case DRAFT, PENDING_PAYMENT, ON_HOLD, SHIPPED, DELIVERY_FAILED, DELIVERED, RETURN_REQUESTED, RETURNED,
                 REFUNDED, PARTIALLY_REFUNDED, COMPLETED, CANCELLED, CLOSED -> false;
        };
    }

    /**
     * The transition table, as one exhaustive switch.
     *
     * <p>Written here rather than as scattered {@code if (status != X) throw} checks so that the whole machine is
     * readable in one place, and so that adding a status forces every branch to be revisited. The database holds the
     * same edges ({@code ordering.order_status_transition}).</p>
     */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            // CONFIRMED / ON_HOLD: a credit order, within or over its limit (SCRUM-427).
            case DRAFT -> target == PENDING_PAYMENT || target == CONFIRMED || target == ON_HOLD || target == CANCELLED;
            // kltn-docs 15 BR-02: nothing leaves PENDING_PAYMENT for production or the warehouse directly. No
            // PAYMENT_FAILED (G3-Q2): a bank transfer that never comes is a cancellation once the hold runs out.
            case PENDING_PAYMENT -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == IN_PRODUCTION || target == READY_TO_FULFILL || target == ON_HOLD
                    || target == CANCELLED;
            // PARTIALLY_FULFILLED: PACK-EARLY (lead's decision 2026-10-21) - the stock lines of a mixed order that allows
            // partial shipments were packed and handed over while its print lines are still printed.
            case IN_PRODUCTION -> target == READY_TO_FULFILL || target == PARTIALLY_FULFILLED || target == ON_HOLD
                    || target == CANCELLED;
            // PACKED directly: an order of printed goods only, or whose stock was picked while it was printed (17 §4.2.6).
            // PARTIALLY_FULFILLED: PACK-EARLY - its stock, packed while it was printed, handed over before the prints.
            case READY_TO_FULFILL -> target == PICKING || target == PACKED || target == PARTIALLY_FULFILLED
                    || target == ON_HOLD || target == CANCELLED;
            case PICKING -> target == PACKED || target == PARTIALLY_FULFILLED || target == ON_HOLD
                    || target == CANCELLED;
            // PACKED: the rest packed (17 §5). SHIPPED: the rest handed over. SHIPPED / DELIVERY_FAILED / DELIVERED /
            // RETURNED: the rest closed by staff, the order then shows its shipments.
            case PARTIALLY_FULFILLED -> target == PACKED || target == SHIPPED || target == DELIVERY_FAILED
                    || target == DELIVERED || target == RETURNED || target == ON_HOLD;
            // Not CANCELLED: a completed package goes through a return (decision 2026-10-17). PARTIALLY_FULFILLED: only
            // part of it was handed over.
            case PACKED -> target == SHIPPED || target == PARTIALLY_FULFILLED || target == ON_HOLD;
            // Back to where it was held from; CONFIRMED / PENDING_PAYMENT also as a credit decision (15 §4.3).
            case ON_HOLD -> target == CONFIRMED || target == PENDING_PAYMENT || target == IN_PRODUCTION
                    || target == READY_TO_FULFILL || target == PICKING || target == PARTIALLY_FULFILLED
                    || target == PACKED || target == CANCELLED;
            // PARTIALLY_FULFILLED: a shipment came back while the others arrived.
            case SHIPPED -> target == DELIVERED || target == DELIVERY_FAILED || target == PARTIALLY_FULFILLED;
            case DELIVERY_FAILED -> target == DELIVERED || target == RETURNED || target == PARTIALLY_FULFILLED;
            case DELIVERED -> target == COMPLETED || target == RETURN_REQUESTED;
            // DELIVERED: the request was refused - back to waiting for the return window and the money, as any
            // delivered order (lead's decision 2026-10-21; 17 §5 wrote COMPLETED, which would skip both checks).
            case RETURN_REQUESTED -> target == RETURNED || target == DELIVERED;
            case RETURNED -> target == REFUNDED || target == PARTIALLY_REFUNDED;
            // RETURN_REQUESTED: a further claim after a return gave money back (Sales open it, 03 §12), or a late claim
            // Sales accepted after the order completed - until the order closes for good (BR-05), it hears its returns.
            case REFUNDED, PARTIALLY_REFUNDED, COMPLETED -> target == CLOSED || target == RETURN_REQUESTED;
            case CANCELLED, CLOSED -> false;
        };
    }

    /** Every edge of {@link #canTransitionTo}, as {@code FROM>TO} strings; what the database table must hold. */
    public static Set<String> edges() {
        return Arrays.stream(values())
                .flatMap(from -> Arrays.stream(values()).filter(from::canTransitionTo).map(to -> from + ">" + to))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /** The statuses {@link #countsTowardCreditExposure} names, for the exposure query. */
    public static Set<OrderStatus> creditExposureStatuses() {
        EnumSet<OrderStatus> counted = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : values()) {
            if (status.countsTowardCreditExposure()) {
                counted.add(status);
            }
        }
        return counted;
    }
}

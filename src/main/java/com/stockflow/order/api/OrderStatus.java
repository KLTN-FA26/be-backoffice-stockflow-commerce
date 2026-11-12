package com.stockflow.order.api;

import java.util.Arrays;
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
 *   DRAFT ─contract sent─▶ PENDING_CONTRACT ─signed─▶ PENDING_PAYMENT (an installment on signing) / CONFIRMED /
 *     ▲                       │                       ON_HOLD (credit, over the limit or overdue)
 *     └──contract cancelled───┘
 *   PENDING_PAYMENT ─paid─▶ CONFIRMED ─release─▶ READY_TO_FULFILL
 *                           CONFIRMED ─short, a production order sent─▶ AWAITING_STOCK ─release─▶ READY_TO_FULFILL
 *   DRAFT ─▶ PENDING_PAYMENT / CONFIRMED / ON_HOLD: checkout, until it is removed (4B-10)
 *
 *   READY_TO_FULFILL ─▶ PICKING ─▶ PACKED ─handover─▶ SHIPPED ─▶ DELIVERED ─▶ COMPLETED ─▶ CLOSED
 *                                                                   └─▶ DELIVERY_FAILED ─▶ DELIVERED / RETURNED
 *                     PICKING / PACKED ─part handed over─▶ PARTIALLY_FULFILLED ─rest packed─▶ PACKED
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
 * <p><b>No printing here.</b> The goods are printed outside the system and received into stock before an order is
 * released (kltn-docs 07/11), so release goes straight to READY_TO_FULFILL. {@link #IN_PRODUCTION} and its edges stay
 * only because status history written before that change carries them.</p>
 *
 * <p><b>Waiting for stock</b> (4A-6, 17 §4.2 step 5, §5.1, BR-04). An order of a contract whose next delivery batch is
 * short at its shipping warehouse waits {@link #AWAITING_STOCK} once a production order for that batch is sent to a
 * producer. READY_TO_FULFILL keeps meaning "released" here ({@code ck_order_release_status}, fulfillment opens a task
 * only for it), so the order leaves AWAITING_STOCK when it is released with every carton held, as a CONFIRMED order
 * does - not the moment the last carton is put away.</p>
 *
 * <p><b>Hold</b> (17 §5, 09 edge). {@code ON_HOLD} is entered from any status up to {@code PACKED} (and
 * {@code PARTIALLY_FULFILLED}) and left back to the status it was entered from, or to {@code CANCELLED}; a credit hold
 * (an order placed over its limit) is decided through credit approval instead.</p>
 *
 * <p><b>A shipment that comes back</b> (WF-05 fix plan, sections 2-3; 09 §4.3 step 3). The order no longer follows a
 * returned shipment to {@code RETURNED} or {@code PARTIALLY_FULFILLED} by itself: it goes {@code ON_HOLD} for
 * {@code DELIVERY_FAILED} - from {@code SHIPPED} and {@code DELIVERY_FAILED} too - and waits there for Sales to decide
 * its delivery failure case. Decided, it is {@code PARTIALLY_FULFILLED} (the goods are owed a delivery again, or are
 * closed with the rest); {@code RETURNED} is reached only when Sales cancelled everything that was not delivered and
 * nothing of the order was.</p>
 *
 * <p><b>Cancellation</b> stops at the first completed package (decision 2026-10-17, BR-ORD-004): {@code PACKED} and
 * later go through a return. There is no {@code PAYMENT_FAILED}: a B2B bank transfer has no failure to report (G3-Q2).</p>
 */
public enum OrderStatus {

    /**
     * Not yet an order the customer is bound by: made from an accepted quote (kltn-docs 14 §4.2), its contract being
     * drawn up. No stock held, no terms, no credit checked; the customer does not see it (17 §5.2).
     */
    DRAFT,

    /**
     * 4B-5 (14 §4.3 step 13, §5.3): its contract was sent and waits to be signed. Still no stock held; the customer
     * sees it and may ask to cancel it (17 §4.8).
     */
    PENDING_CONTRACT,

    /** Submitted, stock reserved, waiting for the prepayment or the deposit. */
    PENDING_PAYMENT,

    /**
     * Cleared to go ahead: paid in full (prepaid), deposit received (kltn-docs 15 BR-02) or credit within the limit.
     * Its stock holds are pinned; the coordinator may release it.
     */
    CONFIRMED,

    /**
     * 4A-6 (kltn-docs 17 §4.2 step 5, §4.4 step 15, §5.1 "Awaiting Stock", BR-04): an order of a contract short of
     * finished goods for its next delivery batch at its shipping warehouse, with a production order for that batch sent
     * to a producer. What is available there is held for it and topped up as goods are put away; it is released once
     * every carton of its next delivery batch is held (4B-8 releases batch by batch). The customer sees "Đang chờ hàng"
     * (17 §5.2).
     */
    AWAITING_STOCK,

    /**
     * Legacy: released with lines printed in house (SCRUM-423). No order enters it since in-house production was
     * removed (V20261108000100 moved the orders still in it on); kept because status history rows carry it, and
     * {@code ordering.order_status_transition} still lists its edges.
     */
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

    /**
     * The goods came back to the warehouse through an RMA, or - after a failed delivery - Sales cancelled everything
     * that was not delivered and nothing of the order was. A shipment coming back no longer moves the order here by
     * itself: it holds it ({@code HoldReason.DELIVERY_FAILED}).
     */
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
            case DRAFT, PENDING_CONTRACT, PENDING_PAYMENT, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL,
                 PICKING, PARTIALLY_FULFILLED, PACKED, ON_HOLD, SHIPPED, DELIVERY_FAILED, DELIVERED, RETURN_REQUESTED,
                 RETURNED, REFUNDED, PARTIALLY_REFUNDED, COMPLETED -> false;
        };
    }

    /** True while the order still holds stock that must be released if it is cancelled. */
    public boolean holdsStock() {
        return switch (this) {
            case PENDING_PAYMENT, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL, PICKING,
                 PARTIALLY_FULFILLED, PACKED, ON_HOLD -> true;
            case DRAFT, PENDING_CONTRACT, SHIPPED, DELIVERY_FAILED, DELIVERED, RETURN_REQUESTED, RETURNED, REFUNDED,
                 PARTIALLY_REFUNDED, COMPLETED, CANCELLED, CLOSED -> false;
        };
    }

    /**
     * Whether a customer may cancel on their own: never (kltn-docs 17 §4.8, 4B-5). Every order is bound by a contract
     * once the customer sees it, so the customer sends a cancellation request and Sales decides - cancelling costs what
     * the contract says. Kept as a method so that the callers which once let the customer cancel read as such.
     */
    public boolean customerMayCancelDirectly() {
        return false;
    }

    /**
     * The statuses an order may be put on hold from, and resolved back to (17 §4.9 step 35, §5, 09 edge "sai địa chỉ").
     */
    public boolean mayBePutOnHold() {
        return switch (this) {
            case CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED, PACKED -> true;
            case DRAFT, PENDING_CONTRACT, PENDING_PAYMENT, ON_HOLD, SHIPPED, DELIVERY_FAILED, DELIVERED,
                 RETURN_REQUESTED, RETURNED, REFUNDED, PARTIALLY_REFUNDED, COMPLETED, CANCELLED, CLOSED -> false;
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
            // PENDING_CONTRACT: its contract sent (4B-5). PENDING_PAYMENT / CONFIRMED / ON_HOLD: checkout's - a credit
            // order within or over its limit (SCRUM-427) - until checkout is removed (4B-10).
            case DRAFT -> target == PENDING_CONTRACT || target == PENDING_PAYMENT || target == CONFIRMED
                    || target == ON_HOLD || target == CANCELLED;
            // 14 §5.3: signed - waiting for an installment on signing, confirmed, or held for credit (15 BR-11); back to
            // DRAFT when the contract is cancelled to be drawn up again.
            case PENDING_CONTRACT -> target == DRAFT || target == PENDING_PAYMENT || target == CONFIRMED
                    || target == ON_HOLD || target == CANCELLED;
            // kltn-docs 15 BR-02: nothing leaves PENDING_PAYMENT for production or the warehouse directly. No
            // PAYMENT_FAILED (G3-Q2): a bank transfer that never comes is a cancellation once the hold runs out.
            case PENDING_PAYMENT -> target == CONFIRMED || target == CANCELLED;
            // AWAITING_STOCK (4A-6, BR-04): its next delivery batch is short and a production order for it was sent.
            case CONFIRMED -> target == AWAITING_STOCK || target == IN_PRODUCTION || target == READY_TO_FULFILL
                    || target == ON_HOLD || target == CANCELLED;
            // 17 §5.1: READY_TO_FULFILL when released with every carton held (the release, as from CONFIRMED);
            // PARTIALLY_FULFILLED when what it has is handed over first (17 §4.4 step 15, a partial shipment - 4B-8).
            case AWAITING_STOCK -> target == READY_TO_FULFILL || target == PARTIALLY_FULFILLED || target == ON_HOLD
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
            case ON_HOLD -> target == CONFIRMED || target == PENDING_PAYMENT || target == AWAITING_STOCK
                    || target == IN_PRODUCTION || target == READY_TO_FULFILL || target == PICKING
                    || target == PARTIALLY_FULFILLED || target == PACKED || target == CANCELLED;
            // PARTIALLY_FULFILLED: before WF-05's fix, a shipment that came back while the others arrived; kept for the
            // history. ON_HOLD: a shipment came back - the order waits for Sales' decision (hold DELIVERY_FAILED).
            case SHIPPED -> target == DELIVERED || target == DELIVERY_FAILED || target == PARTIALLY_FULFILLED
                    || target == ON_HOLD;
            case DELIVERY_FAILED -> target == DELIVERED || target == RETURNED || target == PARTIALLY_FULFILLED
                    || target == ON_HOLD;
            // DELIVERY_FAILED: only a delivery the warehouse manager corrected by hand, the carrier having reported it
            // wrongly, before the order completed (09 §4.3 step 5, E17; V20261108001800).
            case DELIVERED -> target == COMPLETED || target == RETURN_REQUESTED || target == DELIVERY_FAILED;
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
}

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
 * the trigger {@code tg_order_status_transition}); {@code OrderLifecycleIntegrationTest#triggerRefusesJumps} checks
 * the two are equal.</p>
 *
 * <pre>
 *   DRAFT ─contract sent─▶ PENDING_CONTRACT ─signed─▶ PENDING_PAYMENT (an installment on signing) / CONFIRMED /
 *     ▲                       │                       ON_HOLD (credit, over the limit or overdue)
 *     └──contract cancelled───┘
 *   PENDING_PAYMENT ─paid─▶ CONFIRMED ─release─▶ READY_TO_FULFILL
 *                           CONFIRMED ─short, a production order sent─▶ AWAITING_STOCK ─release─▶ READY_TO_FULFILL
 *
 *   READY_TO_FULFILL ─▶ PICKING ─▶ PACKED ─handover─▶ SHIPPED ─▶ DELIVERED ─▶ COMPLETED ─▶ CLOSED
 *                                                                   └─▶ DELIVERY_FAILED ─▶ DELIVERED / RETURNED
 *                     PICKING ─completed in part─▶ PARTIALLY_FULFILLED ─rest packed, nothing left yet─▶ PACKED
 *                     PACKED ─part handed over─▶ PARTIALLY_FULFILLED ─rest handed over─▶ SHIPPED
 *                     (a part handed over: the rest - of the batch, or a later batch - is packed while it stays
 *                      PARTIALLY_FULFILLED, WF4-PF-NEXT)
 *   DELIVERED ─▶ RETURN_REQUESTED ─▶ RETURNED ─▶ REFUNDED / PARTIALLY_REFUNDED ─▶ CLOSED
 *                                       ▲                 │
 *                                       └─refund voided───┘
 * </pre>
 *
 * <p><b>Closing waits for the refund</b> (TT-4). COMPLETED, REFUNDED and PARTIALLY_REFUNDED close a configured number
 * of days later only once nothing is owed either way: neither by the customer on the installments nor by the seller as
 * a refund still to make. A refund record voided after the order moved on to REFUNDED / PARTIALLY_REFUNDED takes it
 * back to RETURNED, waiting for its refund again; recorded again, it moves forward as before. CLOSED is never
 * reopened: a void after closing shows only on the accountant's list of refunds still to make.</p>
 *
 * <p><b>No way round the contract</b> (14 §5.3). A draft leaves only for PENDING_CONTRACT or CANCELLED: the old
 * checkout's DRAFT → PENDING_PAYMENT / CONFIRMED / ON_HOLD went with checkout (4B-10, removed from the table in
 * V20261124000200), so an order is confirmed, held for credit or waits for money only once its contract is signed.</p>
 *
 * <p><b>Split shipments</b> (decision G4-Q3, 2026-10-19). {@code PARTIALLY_FULFILLED} is kltn-docs 17's "Partially
 * Fulfilled" and 09's "đơn ở trạng thái giao một phần": the order goes out in parts. It is entered when the check station
 * completes part of what the order wants now (08 §4.4 step 8 - the owner's decision WF4-PF-STATUS of 2026-11-12: at
 * once, not when that part is handed over), when part of it is handed over with the rest still to go (a later delivery
 * batch, 17 §4.5), and when a shipment comes back while others arrived. It is left for {@code PACKED} only when the
 * rest is packed before anything was handed over: once a part left, later delivery batches and the rest of a batch are
 * picked, checked and handed over while it stays PARTIALLY_FULFILLED (17 §4.5; owner decision WF4-PF-NEXT,
 * 2026-11-13). It is left when the rest is handed over ({@code SHIPPED}), or closed by staff, after which the order
 * shows what its shipments say; cartons labelled for it and not handed over when the rest is closed stay on it and are
 * delivered, the order PARTIALLY_FULFILLED until their handover (WF4-CLOSE-REST, 2026-11-13). The order shows
 * what its shipments say ({@code SHIPPED}, {@code DELIVERY_FAILED}, {@code DELIVERED}, {@code RETURNED}). An order with every
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
 * <p><b>A shipment that comes back</b> (RTM-RETURNED, owner 18-19/11; kltn-docs 09 §4.3 step 4, 17 §5.1). Its units are
 * closed - the goods are sent again only on a new order - and their money settled on the installments. The order
 * follows its shipments: {@code RETURNED} when nothing of it was delivered, {@code PARTIALLY_FULFILLED} while other
 * shipments arrived, and from there {@code REFUNDED} / {@code PARTIALLY_REFUNDED} once the refund is recorded -
 * {@code PARTIALLY_FULFILLED} straight to {@code PARTIALLY_REFUNDED}, never through {@code RETURNED} (docs PR #42). The
 * WF-05 hold for Sales to deliver again is gone.</p>
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

    /**
     * Its contract signed, waiting for the installment due on signing (14 §4.3 step 15, 15 BR-07). Nothing is reserved
     * yet: stock is held only once it is CONFIRMED and Sales chose its shipping warehouse (17 §4.2). {@link #holdsStock}
     * still answers true so that a cancellation from here releases whatever is held - which is nothing.
     */
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
     * Going out in parts (split shipment, decision G4-Q3): packed in part at the check station (08 §4.4, WF4-PF-STATUS
     * 2026-11-12), or part of it handed over while the rest - of this delivery batch or of a later one - is still to
     * go, or came back.
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
     * The goods of a claim came back to the warehouse (RMA), or nothing of the order was delivered: every shipment
     * came back from the carrier or was closed as lost (RTM-RETURNED; WF-05 Q-7, Q-12). The carrier's report moves the
     * order here; the units and the money wait for the return receipt to be confirmed. Back to the delivery path when
     * its claims end without money (X-8, {@code Order.returnsEndedWithoutRefund}).
     */
    RETURNED,

    /**
     * Refunded in full (or the receivable reduced in full, kltn-docs 15 §4.4). Back to {@link #RETURNED} when a refund
     * record of it is voided (TT-4).
     */
    REFUNDED,

    /** Refunded in part. Back to {@link #RETURNED} when a refund record of it is voided (TT-4). */
    PARTIALLY_REFUNDED,

    /** Closed successfully: delivered, paid, and the return window has passed. */
    COMPLETED,

    CANCELLED,

    /**
     * Closed for good (BR-05: immutable); reached a configured number of days after COMPLETED / REFUNDED /
     * PARTIALLY_REFUNDED, and only once neither the customer nor the seller owes anything on it (TT-4: no refund still
     * to make). Never reopened: a refund voided afterwards is the accountant's to follow, not the order's.
     */
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
            // 14 §5.3: its contract sent (4B-5), or cancelled. Checkout's edges to PENDING_PAYMENT / CONFIRMED / ON_HOLD
            // went with checkout (4B-10): a draft is placed only through its signed contract.
            case DRAFT -> target == PENDING_CONTRACT || target == CANCELLED;
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
            // PARTIALLY_FULFILLED: completed in part at the check station (08 §4.4, WF4-PF-STATUS 2026-11-12).
            case PICKING -> target == PACKED || target == PARTIALLY_FULFILLED || target == ON_HOLD
                    || target == CANCELLED;
            // PACKED: the rest packed while nothing was handed over yet (17 §5; WF4-PF-NEXT). SHIPPED: the rest handed
            // over. SHIPPED / DELIVERY_FAILED / DELIVERED /
            // RETURNED: the rest closed by staff, the order then shows its shipments.
            // PARTIALLY_REFUNDED: a shipment came back while the others arrived and its part is refunded
            // (RTM-RETURNED-PARTIAL, owner 19/11; 17 §5.1 edge, docs PR #42). CLOSED: the same with nothing made
            // refundable - nothing was refunded, so not "partly refunded" (WF-05 BE-45, Order.closeWithoutRefund).
            case PARTIALLY_FULFILLED -> target == PACKED || target == SHIPPED || target == DELIVERY_FAILED
                    || target == DELIVERED || target == RETURNED || target == ON_HOLD || target == PARTIALLY_REFUNDED
                    || target == CLOSED;
            // Not CANCELLED: a completed package goes through a return (decision 2026-10-17). PARTIALLY_FULFILLED: only
            // part of it was handed over.
            case PACKED -> target == SHIPPED || target == PARTIALLY_FULFILLED || target == ON_HOLD;
            // Back to where it was held from; CONFIRMED / PENDING_PAYMENT also as a credit decision (15 §4.3).
            case ON_HOLD -> target == CONFIRMED || target == PENDING_PAYMENT || target == AWAITING_STOCK
                    || target == IN_PRODUCTION || target == READY_TO_FULFILL || target == PICKING
                    || target == PARTIALLY_FULFILLED || target == PACKED || target == CANCELLED;
            // PARTIALLY_FULFILLED: before WF-05's fix, a shipment that came back while the others arrived; kept for the
            // history. ON_HOLD: kept for orders held before RTM-RETURNED (hold DELIVERY_FAILED); a shipment coming back
            // no longer holds the order.
            case SHIPPED -> target == DELIVERED || target == DELIVERY_FAILED || target == PARTIALLY_FULFILLED
                    || target == ON_HOLD;
            case DELIVERY_FAILED -> target == DELIVERED || target == RETURNED || target == PARTIALLY_FULFILLED
                    || target == ON_HOLD;
            // DELIVERY_FAILED: only a delivery the warehouse manager corrected by hand, the carrier having reported it
            // wrongly, before the order completed (09 §4.3 step 5, E17; V20261108001800).
            case DELIVERED -> target == COMPLETED || target == RETURN_REQUESTED || target == DELIVERY_FAILED;
            // DELIVERED: the request was refused - back to waiting for the return window and the money, as any
            // delivered order (lead's decision 2026-10-21; 17 §5 wrote COMPLETED, which would skip both checks).
            // WF-05 BE-40: back to whatever the claim was opened from - COMPLETED (not completed again), REFUNDED,
            // PARTIALLY_REFUNDED - and DELIVERY_FAILED / PARTIALLY_FULFILLED when a delivery was corrected to a
            // failure while the claim was open (BE-46): the order goes where its shipments say.
            case RETURN_REQUESTED -> target == RETURNED || target == DELIVERED || target == COMPLETED
                    || target == REFUNDED || target == PARTIALLY_REFUNDED || target == DELIVERY_FAILED
                    || target == PARTIALLY_FULFILLED;
            // X-8 (WF-05 BE-45): its claims ended and none settled anything - back to the delivery path, COMPLETED when
            // it had completed before the claim; DELIVERY_FAILED / PARTIALLY_FULFILLED as above (BE-46).
            // CLOSED: the carrier brought every shipment back or lost it, and nothing was made refundable (WF-05 BE-45,
            // Order.closeWithoutRefund) - never an order a customer's return made RETURNED.
            case RETURNED -> target == REFUNDED || target == PARTIALLY_REFUNDED || target == DELIVERED
                    || target == COMPLETED || target == DELIVERY_FAILED || target == PARTIALLY_FULFILLED
                    || target == CLOSED;
            // RETURN_REQUESTED: a further claim after a return gave money back (Sales open it, 03 §12), or a late claim
            // Sales accepted after the order completed - until the order closes for good (BR-05), it hears its returns.
            // RETURNED (TT-4): a refund record of a refunded order was voided - it waits for its refund again.
            case REFUNDED -> target == CLOSED || target == RETURN_REQUESTED || target == RETURNED;
            // PARTIALLY_FULFILLED: the voided refund was of a part that came back, the rest delivered (RTM-RETURNED-PARTIAL).
            case PARTIALLY_REFUNDED -> target == CLOSED || target == RETURN_REQUESTED || target == RETURNED
                    || target == PARTIALLY_FULFILLED;
            case COMPLETED -> target == CLOSED || target == RETURN_REQUESTED;
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

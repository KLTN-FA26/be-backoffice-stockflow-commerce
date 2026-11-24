package com.stockflow.payment.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the payment module asks the order module about the order of an installment, a receipt or a refund (4B-10, after
 * the money ledger went): whose order it is, where it stands, and when it was delivered in full.
 *
 * <p>An interface in {@code payment :: api} implemented <b>by the order module</b>, as {@code product}'s
 * {@code SkuReferenceProbe}: {@code order} already depends on {@code payment :: api} (installments, settlements, the
 * credit position), so payment calling {@code OrderService} would close a cycle {@code ModularityTest} rejects. Payment
 * depends on this interface only; Spring hands it the order module's implementation.</p>
 *
 * <p>{@link #lockForPayment} takes the order's row lock in the caller's transaction: a receipt, a void, a refund record,
 * a cancellation and a handover of one order happen one after the other, orders always before installments.</p>
 */
public interface InstallmentOrders {

    /**
     * The order, its row locked until the caller's transaction ends; empty when there is no such order. Lock orders
     * before payment's own rows (installments, receipts, refund records), always: that is the order every caller takes
     * them in.
     */
    Optional<InstallmentOrder> lockForPayment(UUID orderId);

    /** The same without a lock, for reading. */
    Optional<InstallmentOrder> find(UUID orderId);

    /**
     * 4B-6 (15 BR-09, D23): when the order was delivered in full - its last delivery, or the rest closed after it -
     * once it is; empty while goods are still to be delivered (or for no such order). The day a contract's
     * installments paid after delivery count from.
     */
    Optional<Instant> deliveredInFullAt(UUID orderId);

    /**
     * WF-05 Q-3, Q-19 (audit r2 money M-1 / lifecycle M-2): when an order that delivered nothing ended - its deliveries
     * over, every shipment brought back (its receipt confirmed) or lost, its rest closed or never there - at the latest
     * of those closures; empty otherwise (something was delivered: {@link #deliveredInFullAt}), and while anything is
     * still to come. The day its installments after delivery count from, which {@link #deliveredInFullAt} cannot give
     * it: Q-3's "later of the last delivery and the goods back" is the goods back when nothing was delivered. Asked by
     * every path that may end such an order - the last shipment's closure and a closure of its rest alike - so the
     * order in which they happen does not decide whether the plate fee Q-19 keeps ever falls due.
     */
    Optional<Instant> endedWithNothingDeliveredAt(UUID orderId);

    /**
     * WF-05 Q-12 (audit r2 contract M-1): those of {@code shipmentIds} Sales closed as lost - the carrier lost the
     * parcel and confirmed it - rather than brought back to the warehouse. Their settlements are SHIPMENT_RETURNED like
     * a carrier's return (the same money rule, Q-2), but a paper that says the goods came back to the warehouse would
     * record a stock-in that never happened (Luật Kế toán Điều 13 khoản 1): the refund confirmation and the debt
     * reconciliation word them by this. Empty for none.
     */
    java.util.Set<UUID> lostShipments(java.util.Collection<UUID> shipmentIds);

    /**
     * 4B-8 (15 §4.1, 17 §4.5 "Đợt thanh toán gắn đợt giao"): the delivery batches of the order delivered in full - every
     * unit of the batch in a delivered shipment - each with the moment its last unit was delivered. An installment paid
     * after delivery that is tied to a batch is due from that moment, not from the whole order's delivery. Empty for an
     * order without a delivery schedule, or none delivered yet.
     */
    java.util.Map<UUID, Instant> batchesDeliveredAt(UUID orderId);

    /**
     * WF-05 decision Q-20 (BE-33): the goods of the order handed over to a carrier, and the delivery batches they
     * carried - every shipment the order knows of, whatever became of it. {@link HandedOverGoods#NONE} for no such order
     * or none handed over. Read in the caller's transaction, after {@link #lockForPayment}.
     */
    HandedOverGoods handedOverGoods(UUID orderId);
}

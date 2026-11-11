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
     * 4B-8 (15 §4.1, 17 §4.5 "Đợt thanh toán gắn đợt giao"): the delivery batches of the order delivered in full - every
     * unit of the batch in a delivered shipment - each with the moment its last unit was delivered. An installment paid
     * after delivery that is tied to a batch is due from that moment, not from the whole order's delivery. Empty for an
     * order without a delivery schedule, or none delivered yet.
     */
    java.util.Map<UUID, Instant> batchesDeliveredAt(UUID orderId);
}

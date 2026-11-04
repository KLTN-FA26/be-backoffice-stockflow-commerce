package com.stockflow.payment.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What the payment module asks the order module before it moves money onto or off an order (G2-3, the money ledger):
 * whose order it is, what it is worth now, and whether money taken off it would leave a confirmed order unpaid.
 *
 * <p>An interface in {@code payment :: api} implemented <b>by the order module</b>, as {@code product}'s
 * {@code SkuReferenceProbe}: {@code order} already depends on {@code payment :: api} (the credit position), so payment
 * calling {@code OrderService} would close a cycle {@code ModularityTest} rejects. Payment depends on this interface
 * only; Spring hands it the order module's implementation.</p>
 *
 * <p>Synchronous on purpose: an allocation is measured against what the order may still take, and two allocations to
 * one order must not both fit into the same gap. {@link #lockForPayment} takes the order's row lock in the caller's
 * transaction, so allocations, reversals, a cancellation and a handover of one order happen one after the other.</p>
 */
public interface OrderPayables {

    /**
     * The order, its row locked until the caller's transaction ends; empty when there is no such order. Lock orders
     * before payment's own rows (receivables, then transfers), always: that is the order every caller takes them in.
     */
    Optional<PayableOrder> lockForPayment(UUID orderId);

    /** The same without a lock, for reading. */
    Optional<PayableOrder> find(UUID orderId);
}

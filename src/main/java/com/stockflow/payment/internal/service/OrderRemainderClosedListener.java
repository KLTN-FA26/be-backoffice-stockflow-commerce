package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderRemainderClosed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The rest of a partly fulfilled order was closed (G3-2, split shipments; kltn-docs 17 §4.8): if that left the order
 * delivered in full, a contract order's installments paid after delivery are due from the day it was (4B-6, 15 D23);
 * if it ended an order that delivered nothing - its shipments brought back or lost before - from the day it ended
 * (WF-05 Q-3, Q-19; audit r2 money M-1: the same rule as the shipment's closure, {@code InstallmentService#follow}).
 *
 * <p>The closure's money is not this listener's: what the installments still owe was lowered by the goods closed, and
 * the surplus made refundable, in the closing transaction ({@code OrderSettlementService}, 4B-9). The refunds a closure
 * asked of the money ledger went with the ledger (4B-10).</p>
 */
@Component("paymentOrderRemainderClosedListener")
class OrderRemainderClosedListener {

    private final InstallmentService installments;

    OrderRemainderClosedListener(InstallmentService installments) {
        this.installments = installments;
    }

    @ApplicationModuleListener
    public void on(OrderRemainderClosed event) {
        installments.delivered(event.orderId(), event.closedAt(), false);
    }
}

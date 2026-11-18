package com.stockflow.order.internal.service;

import com.stockflow.contracts.OrderDelivered;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A shipment of an order was delivered (WF-05 TH-1): when that was its last batch, the order's returns are looked at
 * again ({@link ReturnRequestService#followDelivery}). A return may be opened on batch 1 while batch 2 is still on its
 * way, and it leaves the order's status alone meanwhile; the moment the order is DELIVERED, a return of it that is
 * still open must hold it at RETURN_REQUESTED, as it would have had it been opened then - or the nightly completion
 * would find a DELIVERED order with a claim open on it.
 *
 * <p>After the delivery committed, in a transaction of its own: recording a delivery is fulfillment's step and must not
 * wait for, or fail with, the returns. One that fails stays in the publication registry and is delivered again; the
 * step reads the order and its returns afresh under the order's lock, so a second delivery changes nothing.</p>
 */
@Component
class OrderDeliveredReturnsListener {

    private final ReturnRequestService returns;

    OrderDeliveredReturnsListener(ReturnRequestService returns) {
        this.returns = returns;
    }

    @ApplicationModuleListener
    public void on(OrderDelivered event) {
        returns.followDelivery(event.orderId(), null);
    }
}

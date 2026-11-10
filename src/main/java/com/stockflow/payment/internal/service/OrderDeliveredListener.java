package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderDelivered;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A contract order delivered in full makes its installments paid after delivery due (4B-6, kltn-docs 15 §4.1 step 2,
 * BR-09). Since 4B-10 nothing else: the receivable a credit order's delivery opened went with the money ledger.
 */
@Component("paymentOrderDeliveredListener")
class OrderDeliveredListener {

    private final InstallmentService installments;

    OrderDeliveredListener(InstallmentService installments) {
        this.installments = installments;
    }

    @ApplicationModuleListener
    public void on(OrderDelivered event) {
        // 4B-6 (15 §4.1 step 2, BR-09): delivered in full, what a contract order pays after delivery is due.
        installments.delivered(event.orderId(), event.deliveredAt(), event.shipmentId() == null);
    }
}

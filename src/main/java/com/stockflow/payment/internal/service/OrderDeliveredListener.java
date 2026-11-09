package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderDelivered;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A delivered credit order becomes a receivable (kltn-docs 15 §4.3 step 2; SCRUM-431); a contract order delivered in
 * full makes its installments paid after delivery due (4B-6).
 */
@Component("paymentOrderDeliveredListener")
class OrderDeliveredListener {

    private final ReceivableService receivables;
    private final InstallmentService installments;

    OrderDeliveredListener(ReceivableService receivables, InstallmentService installments) {
        this.receivables = receivables;
        this.installments = installments;
    }

    @ApplicationModuleListener
    public void on(OrderDelivered event) {
        receivables.orderDelivered(event);
        // 4B-6 (15 §4.1 step 2, BR-09): delivered in full, what a contract order pays after delivery is due.
        installments.delivered(event.orderId(), event.deliveredAt(), event.shipmentId() == null);
    }
}

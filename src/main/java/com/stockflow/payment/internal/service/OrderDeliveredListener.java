package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderDelivered;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** A delivered credit order becomes a receivable (kltn-docs 15 §4.3 step 2; SCRUM-431). */
@Component("paymentOrderDeliveredListener")
class OrderDeliveredListener {

    private final ReceivableService receivables;

    OrderDeliveredListener(ReceivableService receivables) {
        this.receivables = receivables;
    }

    @ApplicationModuleListener
    public void on(OrderDelivered event) {
        receivables.orderDelivered(event);
    }
}

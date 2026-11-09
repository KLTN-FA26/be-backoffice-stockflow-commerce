package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderReleased;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * An order released to be shipped makes its contract's installments paid before delivery due (4B-6, kltn-docs 15 §4.1
 * step 2: "Đơn Ready to Fulfill"). They have no due day: the handover waits for them (BR-08).
 */
@Component("paymentOrderReleasedListener")
class OrderReleasedListener {

    private final InstallmentService installments;

    OrderReleasedListener(InstallmentService installments) {
        this.installments = installments;
    }

    @ApplicationModuleListener
    public void on(OrderReleased event) {
        installments.released(event.orderId(), event.releasedAt());
    }
}

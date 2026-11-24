package com.stockflow.payment.internal.service;

import com.stockflow.contracts.ShipmentReturnSettled;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The units of a shipment the carrier brought back, or lost, were closed and their money settled (WF-05 decisions Q-2,
 * Q-3, Q-7, Q-12, Q-19; BE-02): the installments paid after delivery are reached or moved from what the order now says
 * ({@link InstallmentService#shipmentReturnSettled}).
 *
 * <p>Before this, nothing in payment heard of a carrier return: the order never became DELIVERED, so an installment
 * after delivery stayed NOT_DUE for ever - never overdue, never asked of the customer, the order never closed (audit
 * M-1). The money itself is not this listener's: the order settled it in the closing transaction
 * ({@code PaymentService.settleShipmentReturn}).</p>
 *
 * <p>An order that delivered nothing and expects nothing more has no "delivered in full" to count from: its
 * installments after delivery count from the goods back (Q-3, Q-19) - the order's own fact, read as every other path
 * reads it ({@code InstallmentOrders.endedWithNothingDeliveredAt}), not this event's: a closure of the rest that ends
 * the order last has no such field (audit r2 money M-1). Idempotent on the closure by construction - everything is
 * read again from the order, so hearing the event twice, or late, changes nothing more.
 * After commit, in a transaction of its own ({@code @ApplicationModuleListener}); public, or the proxy would not run it.</p>
 */
@Component("paymentShipmentReturnSettledListener")
class ShipmentReturnSettledListener {

    private final InstallmentService installments;

    ShipmentReturnSettledListener(InstallmentService installments) {
        this.installments = installments;
    }

    @ApplicationModuleListener
    public void on(ShipmentReturnSettled event) {
        installments.shipmentReturnSettled(event.orderId());
    }
}

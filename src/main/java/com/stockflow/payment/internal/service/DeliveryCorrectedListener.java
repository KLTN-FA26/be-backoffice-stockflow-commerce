package com.stockflow.payment.internal.service;

import com.stockflow.contracts.ShipmentStatusChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A delivery corrected to a failure takes back the milestone of the installments paid after it (kltn-docs 09 §4.3
 * step 5, 15 §4.1, E17; WF-05 GH-3): {@link InstallmentService#deliveryCorrected}. Heard as {@code ShipmentStatusChanged}
 * from DELIVERED to DELIVERY_FAILED - the one step that is a correction, since a delivery is otherwise final - which
 * fulfillment publishes in the transaction that corrects the shipment and the order. Payment listens rather than being
 * called: payment cannot depend on fulfillment, and fulfillment does not reach into payment's installments.
 *
 * <p>The goods delivered again bring the order's own {@code OrderDelivered}, which {@link OrderDeliveredListener}
 * already hears: that is what makes the installments due again, from the new day.</p>
 */
@Component("paymentDeliveryCorrectedListener")
class DeliveryCorrectedListener {

    private final InstallmentService installments;

    DeliveryCorrectedListener(InstallmentService installments) {
        this.installments = installments;
    }

    @ApplicationModuleListener
    public void on(ShipmentStatusChanged event) {
        if (event.orderId() == null || !"DELIVERED".equals(event.previousStatus())
                || !"DELIVERY_FAILED".equals(event.status())) {
            return;
        }
        installments.deliveryCorrected(event.orderId());
    }
}

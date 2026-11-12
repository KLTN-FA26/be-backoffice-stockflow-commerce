package com.stockflow.invoicing.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ShipmentStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Voids a shipment's draft invoice when its delivery is corrected to a failure (kltn-docs 09 §4.3 step 5; 21 §4.5): the
 * warehouse manager found the carrier reported a delivery that did not happen, so there is nothing to invoice. Heard
 * as {@code ShipmentStatusChanged} from DELIVERED to DELIVERY_FAILED - the one step that is a correction, since a
 * delivery is otherwise final.
 *
 * <p>If the delivery's own event has not been handled yet, there is no draft to void - and none will be drawn up: by
 * then the shipment is no longer delivered ({@code DeliveredShipments} answers nothing). A shipment delivered again
 * later gets a new draft.</p>
 */
@Component
class DeliveryCorrectionListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCorrectionListener.class);

    private final EInvoiceDrafts drafts;

    DeliveryCorrectionListener(EInvoiceDrafts drafts) {
        this.drafts = drafts;
    }

    @ApplicationModuleListener
    public void on(ShipmentStatusChanged event) {
        if (!"DELIVERED".equals(event.previousStatus()) || !"DELIVERY_FAILED".equals(event.status())) {
            return;
        }
        switch (drafts.voidForCorrectedDelivery(event.shipmentId())) {
            case VOIDED -> log.info("The draft e-invoice of shipment {} was voided: its delivery was corrected to a "
                    + "failure", LogSafe.text(event.shipmentCode()));
            case LEFT_FOR_ADJUSTMENT -> log.warn("Shipment {} of order {} was corrected to a failed delivery after its "
                    + "e-invoice went to the provider: the invoice needs an adjustment",
                    LogSafe.text(event.shipmentCode()), LogSafe.text(event.orderNumber()));
            case NO_INVOICE -> { /* nothing was drawn up for it */ }
        }
    }
}

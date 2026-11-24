package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceDraftFailed;
import com.stockflow.contracts.ShipmentReturnSettled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Puts the rest of an order's fees on an invoice once its deliveries are over after a shipment came back or was lost
 * (WF-05 BE-62, decision Q-19; {@link EInvoiceDrafts#drawUpShippingFeeRest}), as the closing of an order's rest does
 * ({@link OrderRemainderClosedListener}). Only a delivery's invoice ever took a plate fee, so an order whose goods all
 * came back kept the fee - the plates were made, a service done (NĐ 254/2026 Điều 9 khoản 2) - and invoiced it
 * nowhere: the money was collected with no invoice. The shipping fee is waived in proportion to the goods that came
 * back (Q-19, owner 2026-11-23; {@code DeliveredShipments} hands over the share waived): nothing delivered, all of it
 * is waived and none invoiced; something delivered, the rest of what is not waived is invoiced, which is what payment
 * keeps. Heard as {@link ShipmentReturnSettled}
 * with {@code nothingMoreComing}; earlier closures of the same order draw nothing - a later delivery still carries
 * the fee.
 *
 * <p>After the closing committed, in a transaction of its own, as {@link OrderRemainderClosedListener} does: a refusal
 * (the seller not configured, an order not priced in dong) is told as {@link EInvoiceDraftFailed} with no shipment and
 * drawn up by hand later; anything else is thrown and the event delivered again - the draw-up returns the fee invoice
 * standing, so it is never drawn twice.</p>
 */
@Component("invoicingShipmentReturnSettledListener")
class ShipmentReturnSettledListener {

    private static final Logger log = LoggerFactory.getLogger(ShipmentReturnSettledListener.class);

    private final EInvoiceDrafts drafts;
    private final ApplicationEventPublisher events;

    ShipmentReturnSettledListener(EInvoiceDrafts drafts, ApplicationEventPublisher events) {
        this.drafts = drafts;
        this.events = events;
    }

    @ApplicationModuleListener
    public void on(ShipmentReturnSettled event) {
        if (!event.nothingMoreComing()) {
            return;
        }
        try {
            Optional<EInvoiceDrafts.DrawnUp> drawn = drafts.drawUpShippingFeeRest(event.orderId(),
                    event.settledAt());
            if (drawn.isPresent() && drawn.get().created()) {
                log.info("E-invoice {} drawn up for the rest of the fees of order {}, whose deliveries are over",
                        drawn.get().invoice().code(), LogSafe.text(event.orderNumber()));
            }
        } catch (BusinessException refused) {
            log.warn("No e-invoice for the rest of the fees of order {}: {}", LogSafe.text(event.orderNumber()),
                    refused.errorCode());
            events.publishEvent(new EInvoiceDraftFailed(event.orderId(), event.orderNumber(), null,
                    refused.errorCode().name()));
        }
    }
}

package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceDraftFailed;
import com.stockflow.contracts.OrderRemainderClosed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Puts the rest of an order's shipping fee on an invoice when the order's undelivered rest is closed (WF-05 E2;
 * {@link EInvoiceDrafts#drawUpShippingFeeRest}). Closing is the one moment the fee's last share becomes known with no
 * delivery left to carry it - the order stops meaning to deliver anything more - so it is the moment to draw it up,
 * and nothing else in the order's life would.
 *
 * <p>After the closing committed, in a transaction of its own, as {@link OrderDeliveredListener} draws up a delivery's:
 * closing the order must not wait for, or be rolled back by, an invoice. Most closings draw nothing - the order has no
 * shipping fee, a delivery still to be invoiced will take the rest, or the invoices carry the whole fee already.</p>
 *
 * <p>A refusal - the seller not configured, an order not priced in dong - ends here and is told as
 * {@link EInvoiceDraftFailed} with no shipment, so the people who draw invoices up see it; they draw the invoice up by
 * hand once the cause is gone ({@code POST /einvoices} with the order and no shipment). Anything else is thrown and the
 * event delivered again.</p>
 */
@Component("invoicingOrderRemainderClosedListener")
class OrderRemainderClosedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderRemainderClosedListener.class);

    private final EInvoiceDrafts drafts;
    private final ApplicationEventPublisher events;

    OrderRemainderClosedListener(EInvoiceDrafts drafts, ApplicationEventPublisher events) {
        this.drafts = drafts;
        this.events = events;
    }

    @ApplicationModuleListener
    public void on(OrderRemainderClosed event) {
        try {
            Optional<EInvoiceDrafts.DrawnUp> drawn = drafts.drawUpShippingFeeRest(event.orderId(), event.closedAt());
            if (drawn.isPresent() && drawn.get().created()) {
                log.info("E-invoice {} drawn up for the rest of the shipping fee of order {}",
                        drawn.get().invoice().code(), LogSafe.text(event.orderNumber()));
            }
        } catch (BusinessException refused) {
            log.warn("No e-invoice for the rest of the shipping fee of order {}: {}",
                    LogSafe.text(event.orderNumber()), refused.errorCode());
            events.publishEvent(new EInvoiceDraftFailed(event.orderId(), event.orderNumber(), null,
                    refused.errorCode().name()));
        }
    }
}

package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceDraftFailed;
import com.stockflow.contracts.OrderDelivered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Draws up the draft invoice of a shipment when its delivery is recorded (plan §1.4; kltn-docs 21 §4.1; NĐ 254 Điều 9
 * khoản 3: every delivery is invoiced). After the delivery's transaction committed, in its own: recording a delivery
 * must not wait for, or be rolled back by, an invoice.
 *
 * <p>Only a draft is made here. Issuing is the accountant's - or, with automatic issuing on, the job's
 * ({@link EInvoiceJobs}), once the draft is committed.</p>
 *
 * <h2>What is not an invoice is told apart from what failed</h2>
 *
 * <p>A delivery the draft is <b>refused</b> for - the seller is not configured, the shipment delivered nothing an
 * invoice can carry, its delivery was corrected to a failure before this ran - will be refused again however often the
 * event is delivered. So the refusal ends here: this listener returns normally, its event is completed, and
 * {@link EInvoiceDraftFailed} is published in this listener's transaction so the people who draw invoices up are told
 * (an in-app message), not only a log. They draw the draft up from the shipment once the cause is gone
 * ({@code POST /einvoices}).</p>
 *
 * <p>That only works because {@link EInvoiceDrafts#drawUp} runs in a transaction of its own: a refusal rolls that one
 * back and leaves this listener's intact. Were the two one transaction, the exception would mark it rollback-only on
 * its way out of {@code drawUp}, catching it here would change nothing, and the event would stay incomplete - delivered
 * and refused again at every restart.</p>
 *
 * <p>Anything else - the database, a bug - is thrown, so the event stays in the registry and is delivered again: a
 * delivery must not end up without its invoice because the database blinked.</p>
 */
@Component("invoicingOrderDeliveredListener")
class OrderDeliveredListener {

    private static final Logger log = LoggerFactory.getLogger(OrderDeliveredListener.class);

    private final EInvoiceDrafts drafts;
    private final ApplicationEventPublisher events;

    OrderDeliveredListener(EInvoiceDrafts drafts, ApplicationEventPublisher events) {
        this.drafts = drafts;
        this.events = events;
    }

    @ApplicationModuleListener
    public void on(OrderDelivered event) {
        if (event.shipmentId() == null) {
            // An order delivered whole without a shipment: no order through a contract is. Nothing to draw up from.
            log.warn("Order {} was delivered without a shipment: no e-invoice is drawn up for it",
                    LogSafe.text(event.orderNumber()));
            return;
        }
        try {
            EInvoiceDrafts.DrawnUp drawn = drafts.drawUp(event.orderId(), event.shipmentId());
            if (drawn.created()) {
                log.info("E-invoice {} drawn up for shipment {} of order {}", drawn.invoice().code(),
                        event.shipmentId(), LogSafe.text(event.orderNumber()));
            }
        } catch (BusinessException refused) {
            log.warn("No e-invoice for shipment {} of order {}: {}", event.shipmentId(),
                    LogSafe.text(event.orderNumber()), refused.errorCode());
            events.publishEvent(new EInvoiceDraftFailed(event.orderId(), event.orderNumber(), event.shipmentId(),
                    refused.errorCode().name()));
        }
    }
}

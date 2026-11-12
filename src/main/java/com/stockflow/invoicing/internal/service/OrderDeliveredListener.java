package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.OrderDelivered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Draws up the draft invoice of a shipment when its delivery is recorded (plan §1.4; kltn-docs 21 §4.1; NĐ 254 Điều 9
 * khoản 3: every delivery is invoiced). After the delivery's transaction committed, in its own: recording a delivery
 * must not wait for, or be rolled back by, an invoice.
 *
 * <p>Only a draft is made here. Issuing is the accountant's - or, with automatic issuing on, the job's
 * ({@link EInvoiceJobs}), once this transaction has committed the draft.</p>
 *
 * <p>What is not an invoice is told apart from what failed. A delivery with nothing to invoice - no shipment (an order
 * delivered whole without one, which no order through a contract is), a shipment no longer delivered because its
 * outcome was corrected meanwhile - is logged and left: retrying would never produce an invoice. A draft another
 * delivery of the same event already made is left too. Anything else is thrown, so the event stays in the registry and
 * is delivered again: a delivery must not end up without its invoice because the database blinked.</p>
 */
@Component("invoicingOrderDeliveredListener")
class OrderDeliveredListener {

    private static final Logger log = LoggerFactory.getLogger(OrderDeliveredListener.class);

    private final EInvoiceDrafts drafts;

    OrderDeliveredListener(EInvoiceDrafts drafts) {
        this.drafts = drafts;
    }

    @ApplicationModuleListener
    public void on(OrderDelivered event) {
        if (event.shipmentId() == null) {
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
        } catch (BusinessException notInvoiceable) {
            // Not delivered any more, nothing in it, no seller configured: a retry changes none of that. The accountant
            // draws the invoice up from the shipment once the cause is gone (POST /einvoices).
            log.warn("No e-invoice for shipment {} of order {}: {}", event.shipmentId(),
                    LogSafe.text(event.orderNumber()), notInvoiceable.errorCode());
        } catch (DataIntegrityViolationException alreadyDrawnUp) {
            if (!String.valueOf(alreadyDrawnUp.getMostSpecificCause().getMessage())
                    .contains("uk_einvoice_original_per_shipment")) {
                throw alreadyDrawnUp;
            }
            log.info("Shipment {} of order {} already has its e-invoice", event.shipmentId(),
                    LogSafe.text(event.orderNumber()));
        }
    }
}

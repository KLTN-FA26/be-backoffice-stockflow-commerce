package com.stockflow.invoicing.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Voids a shipment's draft invoice, or reduces its issued one in full, when its delivery is corrected to a failure
 * (kltn-docs 09 §4.3 step 5; 21 §4.4, §4.5): the
 * warehouse manager found the carrier reported a delivery that did not happen, so there is nothing to invoice. Heard
 * as {@code ShipmentStatusChanged} from DELIVERED to DELIVERY_FAILED - the one step that is a correction, since a
 * delivery is otherwise final.
 *
 * <p>If the delivery's own event has not been handled yet, there is no draft to void - and none will be drawn up: by
 * then the shipment is no longer delivered ({@code DeliveredShipments} answers nothing). A shipment delivered again
 * later gets a new draft.</p>
 *
 * <p>An invoice already at the provider, or issued, is not voided: it may exist in law, and it now invoices goods that
 * were not delivered. An issued original is reduced in full by the system - the draft adjustment 21 §4.4 asks for, on
 * the basis {@code DELIVERY_FAILED}, with no agreement to wait for ({@link EInvoiceDrafts#reverseForCorrectedDelivery};
 * WF-05 HĐ-3) - and the accountants are told to issue it
 * ({@link EInvoiceNeedsAttention.Cause#DELIVERY_FAILED_REVERSAL_DRAWN_UP}). One still at the provider, replaced, or
 * already being corrected cannot be reduced whole by the system: the accountants are told it needs their adjustment
 * ({@link EInvoiceNeedsAttention.Cause#DELIVERY_FAILED_AFTER_SUBMISSION}). Either way in the same transaction: a log
 * line alone would leave the invoice standing until somebody happened to read the log.</p>
 */
@Component
class DeliveryCorrectionListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCorrectionListener.class);

    private final EInvoiceDrafts drafts;
    private final EInvoiceRepository invoices;
    private final ApplicationEventPublisher events;

    DeliveryCorrectionListener(EInvoiceDrafts drafts, EInvoiceRepository invoices, ApplicationEventPublisher events) {
        this.drafts = drafts;
        this.invoices = invoices;
        this.events = events;
    }

    @ApplicationModuleListener
    public void on(ShipmentStatusChanged event) {
        if (!"DELIVERED".equals(event.previousStatus()) || !"DELIVERY_FAILED".equals(event.status())) {
            return;
        }
        switch (drafts.voidForCorrectedDelivery(event.orderId(), event.shipmentId())) {
            case VOIDED -> log.info("The draft e-invoice of shipment {} was voided: its delivery was corrected to a "
                    + "failure", LogSafe.text(event.shipmentCode()));
            case LEFT_FOR_ADJUSTMENT -> {
                var reversal = drafts.reverseForCorrectedDelivery(event.orderId(), event.shipmentId());
                if (reversal.isPresent()) {
                    EInvoice.Snapshot drawn = reversal.get();
                    log.info("Shipment {} of order {} was corrected to a failed delivery after its e-invoice was "
                            + "issued: the full reduction {} is drawn up", LogSafe.text(event.shipmentCode()),
                            LogSafe.text(event.orderNumber()), LogSafe.text(drawn.code()));
                    events.publishEvent(new EInvoiceNeedsAttention(drawn.id(), drawn.code(), drawn.orderId(),
                            drawn.orderNumber(), EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_REVERSAL_DRAWN_UP, null,
                            null, drawn.issueDueAt()));
                    return;
                }
                log.warn("Shipment {} of order {} was corrected to a failed delivery after its e-invoice went to the "
                        + "provider: the invoice needs an adjustment", LogSafe.text(event.shipmentCode()),
                        LogSafe.text(event.orderNumber()));
                // Same transaction as the correction's handling: told once, or not at all if this rolls back.
                invoices.findLiveOriginalOfShipment(event.shipmentId()).map(EInvoice::snapshot).ifPresent(invoice ->
                        events.publishEvent(new EInvoiceNeedsAttention(invoice.id(), invoice.code(), invoice.orderId(),
                                invoice.orderNumber(), EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_AFTER_SUBMISSION,
                                null, invoice.submittedAt(), invoice.issueDueAt())));
            }
            case NO_INVOICE -> { /* nothing was drawn up for it */ }
        }
    }
}

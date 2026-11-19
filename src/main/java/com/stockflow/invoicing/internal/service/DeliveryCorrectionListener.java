package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceDraftFailed;
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
 * What a shipment's invoice becomes when its delivery is corrected to a failure (kltn-docs 09 §4.3 step 5; 21 §4.4,
 * §4.5; WF-05 HĐ-3): the warehouse manager found the carrier reported a delivery that did not happen, so there is
 * nothing to invoice. Heard as {@code ShipmentStatusChanged} from DELIVERED to DELIVERY_FAILED - the one step that is a
 * correction, since a delivery is otherwise final.
 *
 * <ul>
 *   <li><b>A draft</b>, or one the provider refused: voided. So is a replacement of an issued original that is drawn
 *       up or refused; the original is then reduced as below.</li>
 *   <li><b>Issued</b> - the original, or the replacement standing for the shipment: reduced in full by a draft the
 *       system draws up ({@link EInvoiceDrafts#reverseForCorrectedDelivery}, basis {@code DELIVERY_FAILED}, no
 *       agreement to wait for) and no longer standing for the shipment; the accountants are told to issue it
 *       ({@link EInvoiceNeedsAttention.Cause#DELIVERY_FAILED_REVERSAL_DRAWN_UP}).</li>
 *   <li><b>Still at the provider</b> - the original, or the replacement of an issued original: nothing can be done yet - it may come back issued, or refused. It is marked, the
 *       accountants are told it will be dealt with when the provider answers
 *       ({@link EInvoiceNeedsAttention.Cause#DELIVERY_FAILED_AWAITING_PROVIDER}), and the answer is settled here
 *       ({@link DeliveryFailedInvoiceAnswered}): issued, reduced in full as above; refused, voided - never corrected
 *       and sent again for goods nobody received (a refused replacement also has its original reduced). Delivered
 *       after all before the provider answered - the failure corrected back to delivered, or a carrier's retry - the
 *       mark is taken back and the invoice is an ordinary one again.</li>
 *   <li><b>Already being corrected</b> - an adjustment of it exists, issued or not, or a replacement is being drawn up:
 *       a full reduction could take it below nothing or reduce the wrong invoice, so it stays the accountant's to bring
 *       to nothing by hand ({@link EInvoiceNeedsAttention.Cause#DELIVERY_FAILED_AFTER_SUBMISSION}).</li>
 * </ul>
 *
 * <p>If the delivery's own event has not been handled yet, there is no draft to void - and none will be drawn up: by
 * then the shipment is no longer delivered ({@code DeliveredShipments} answers nothing). A shipment delivered again
 * later gets a new draft: when its invoice was voided or reversed, from the new delivery's own event; when its invoice
 * was still at the provider, that event found it still standing and drew nothing up, so the settling of the answer
 * draws the new one up.</p>
 *
 * <p>Every outcome is recorded with its notice in one transaction: a log line alone would leave the invoice standing
 * until somebody happened to read the log.</p>
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
        if ("DELIVERY_FAILED".equals(event.previousStatus()) && "DELIVERED".equals(event.status())) {
            // Delivered after all (review finding 5): an invoice marked while at the provider is an ordinary one
            // again. A shipment that never had a delivery corrected has nothing marked, and nothing happens.
            int restored = drafts.restoreDelivered(event.orderId(), event.shipmentId());
            if (restored > 0) {
                log.info("Shipment {} of order {} was delivered after all: {} e-invoice(s) at the provider are no "
                        + "longer marked for a failed delivery", LogSafe.text(event.shipmentCode()),
                        LogSafe.text(event.orderNumber()), restored);
            }
            return;
        }
        if (!"DELIVERED".equals(event.previousStatus()) || !"DELIVERY_FAILED".equals(event.status())) {
            return;
        }
        switch (drafts.voidForCorrectedDelivery(event.orderId(), event.shipmentId())) {
            case VOIDED -> log.info("The draft e-invoice of shipment {} was voided: its delivery was corrected to a "
                    + "failure", LogSafe.text(event.shipmentCode()));
            case AWAITING_PROVIDER -> {
                log.info("Shipment {} of order {} was corrected to a failed delivery while its e-invoice was at the "
                        + "provider: it is settled when the provider answers", LogSafe.text(event.shipmentCode()),
                        LogSafe.text(event.orderNumber()));
                // The invoice marked: the original, or the replacement of an issued original (review finding 4).
                invoices.findUnsettledDeliveryFailedOfShipment(event.shipmentId()).stream().map(EInvoice::snapshot)
                        .forEach(invoice -> events.publishEvent(new EInvoiceNeedsAttention(invoice.id(),
                                invoice.code(), invoice.orderId(), invoice.orderNumber(),
                                EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_AWAITING_PROVIDER, null,
                                invoice.submittedAt(), invoice.issueDueAt())));
            }
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
                log.warn("Shipment {} of order {} was corrected to a failed delivery after its e-invoice was issued "
                        + "and is being corrected already: the invoice needs an adjustment by hand",
                        LogSafe.text(event.shipmentCode()), LogSafe.text(event.orderNumber()));
                // Same transaction as the correction's handling: told once, or not at all if this rolls back.
                invoices.findLiveOriginalOfShipment(event.shipmentId()).map(EInvoice::snapshot).ifPresent(invoice ->
                        events.publishEvent(new EInvoiceNeedsAttention(invoice.id(), invoice.code(), invoice.orderId(),
                                invoice.orderNumber(), EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_AFTER_SUBMISSION,
                                null, invoice.submittedAt(), invoice.issueDueAt())));
            }
            case NO_INVOICE -> { /* nothing was drawn up for it */ }
        }
    }

    /**
     * The provider answered for an invoice of a delivery corrected to a failure: it is reduced in full or voided
     * ({@link EInvoiceDrafts#settleCorrectedDelivery}, its own transaction, with its notice), and a shipment delivered
     * again meanwhile is drawn up anew - in a transaction of its own too, after the first let the order's lock go.
     *
     * <p>A draw-up refused is told as {@link EInvoiceDraftFailed}, in this listener's transaction, as the delivery's own
     * listener does ({@code OrderDeliveredListener}): it would be refused again however often this event came, and the
     * accountant draws it up by hand once the cause is gone. Anything else is thrown, and the registry delivers the
     * event again: settling is idempotent - the invoice is found reduced or voided - and drawing up returns the draft
     * already drawn up.</p>
     */
    @ApplicationModuleListener
    public void on(DeliveryFailedInvoiceAnswered event) {
        EInvoiceDrafts.Settled settled = drafts.settleCorrectedDelivery(event.einvoiceId());
        log.info("E-invoice {} of a delivery corrected to a failure was settled: {}", event.einvoiceId(),
                settled.settlement());
        if (!settled.released() || !settled.deliveredAgain()) {
            return;
        }
        try {
            EInvoiceDrafts.DrawnUp drawn = drafts.drawUp(settled.orderId(), settled.shipmentId());
            if (drawn.created()) {
                log.info("E-invoice {} drawn up for shipment {}, delivered again", LogSafe.text(drawn.invoice().code()),
                        settled.shipmentId());
            }
        } catch (BusinessException refused) {
            EInvoice.Snapshot invoice = invoices.findById(event.einvoiceId()).map(EInvoice::snapshot).orElseThrow();
            log.warn("No e-invoice for shipment {} delivered again: {}", settled.shipmentId(), refused.errorCode());
            events.publishEvent(new EInvoiceDraftFailed(settled.orderId(), invoice.orderNumber(),
                    settled.shipmentId(), refused.errorCode().name()));
        }
    }
}

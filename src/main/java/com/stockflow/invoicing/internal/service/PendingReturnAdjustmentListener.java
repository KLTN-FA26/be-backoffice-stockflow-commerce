package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.EInvoiceReturnUnadjusted;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Takes a return's pieces off its shipment's invoice once that invoice is issued (WF-05 BE-28, HĐ-4). A return whose
 * refund was approved before its shipment's invoice was issued could not be adjusted then, and was taken off another
 * delivery's invoice - another day, often another tax period; its pieces are now kept
 * ({@link EInvoiceCorrections#drawUpForReturn}) and this draws their adjustment up as the invoice is issued.
 *
 * <p>Heard as {@link EInvoiceIssued}, after the issue committed, in a transaction of its own: a return the invoice's
 * rules refuse is told to the accountants ({@link EInvoiceReturnUnadjusted}) rather than thrown, since a retry would be
 * refused again; anything else is thrown and the registry delivers the event again - the kept pieces are settled in
 * the transaction that draws them up, so a second delivery draws nothing twice.</p>
 */
@Component
class PendingReturnAdjustmentListener {

    private static final Logger log = LoggerFactory.getLogger(PendingReturnAdjustmentListener.class);

    private final EInvoiceCorrections corrections;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PendingReturnAdjustmentListener(EInvoiceCorrections corrections, ApplicationEventPublisher events, Clock clock) {
        this.corrections = corrections;
        this.events = events;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(EInvoiceIssued event) {
        if (event.shipmentId() == null) {
            return;
        }
        Map<EInvoiceRepository.PendingReturnPieces, EInvoiceCorrections.ReturnAdjustments> placed;
        try {
            placed = corrections.drawUpPendingReturns(event.shipmentId());
        } catch (BusinessException refused) {
            log.warn("Returns waiting for e-invoice {} were not adjusted: {}", LogSafe.text(event.code()),
                    refused.errorCode());
            return;
        }
        placed.forEach((kept, adjustments) -> {
            adjustments.drafts().forEach(draft -> log.info("Adjustment {} drawn up for return {} of order {}, its "
                    + "shipment's invoice {} issued now", LogSafe.text(draft.code()), LogSafe.text(kept.rmaNumber()),
                    LogSafe.text(kept.orderNumber()), LogSafe.text(event.code())));
            announce(kept, EInvoiceReturnUnadjusted.Cause.NOT_ON_AN_ISSUED_INVOICE, adjustments.unplaced());
            announce(kept, EInvoiceReturnUnadjusted.Cause.ON_A_REPLACEMENT, adjustments.onReplacement());
        });
    }

    private void announce(EInvoiceRepository.PendingReturnPieces kept, EInvoiceReturnUnadjusted.Cause cause,
                          Map<UUID, Long> left) {
        if (left.isEmpty()) {
            return;
        }
        events.publishEvent(new EInvoiceReturnUnadjusted(kept.returnRequestId(), kept.rmaNumber(), kept.orderId(),
                kept.orderNumber(), cause, left.size(), left.values().stream().mapToLong(Long::longValue).sum(), null,
                clock.instant()));
    }
}

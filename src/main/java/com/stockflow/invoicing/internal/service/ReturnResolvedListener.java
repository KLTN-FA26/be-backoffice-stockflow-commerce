package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceReturnUnadjusted;
import com.stockflow.contracts.ReturnResolved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws up the decrease adjustments of a return when its refund is approved (WF-05 E4; plan §1.4; TT 91/2026/TT-BTC
 * Điều 10 khoản 5 điểm c.1: for goods returned the seller draws up an adjustment). Heard as {@code ReturnResolved},
 * which the order module publishes from the transaction that settles the return with payment
 * ({@code PaymentService.settleReturn}); invoicing may read the order module, never the other way round, so the
 * settlement reaches it as an event.
 *
 * <p>The adjustments are <b>drafts</b>: the accountant reads them and issues them. No agreement is asked for - the
 * return is the record.</p>
 *
 * <p>What cannot be adjusted is told apart from what failed, as for a delivery ({@link OrderDeliveredListener}). A
 * refusal by the invoice's rules, and goods no issued invoice holds yet, are left to the accountant - who adjusts for
 * them by hand ({@code POST /einvoices/{id}/adjustments} with the return's id) once the cause is gone - and are
 * announced to the people who issue invoices ({@link EInvoiceReturnUnadjusted}): a log line is read by nobody, and the
 * adjustment is owed. A retry would be refused again, so neither is thrown. Anything else is thrown, so the event stays
 * in the registry and is delivered again.</p>
 *
 * <p>The adjustments are drawn up in a transaction of their own ({@link EInvoiceCorrections#drawUpForReturn}), so a
 * refusal rolls back only them, and this listener's transaction still commits the announcement.</p>
 */
@Component("invoicingReturnResolvedListener")
class ReturnResolvedListener {

    private static final Logger log = LoggerFactory.getLogger(ReturnResolvedListener.class);

    private final EInvoiceCorrections corrections;
    private final ApplicationEventPublisher events;

    ReturnResolvedListener(EInvoiceCorrections corrections, ApplicationEventPublisher events) {
        this.corrections = corrections;
        this.events = events;
    }

    @ApplicationModuleListener
    public void on(ReturnResolved event) {
        if (!event.vatAdjustmentRequired()) {
            return;
        }
        try {
            EInvoiceCorrections.ReturnAdjustments adjustments = corrections.drawUpForReturn(event);
            adjustments.drafts().forEach(draft -> log.info("Adjustment {} drawn up for return {} of order {}",
                    draft.code(), LogSafe.text(event.rmaNumber()), LogSafe.text(event.orderNumber())));
            if (!adjustments.unplaced().isEmpty()) {
                log.warn("Return {} of order {}: {} order line(s) have refunded goods no issued e-invoice could be "
                                + "adjusted for; the accountant adjusts for them by hand",
                        LogSafe.text(event.rmaNumber()), LogSafe.text(event.orderNumber()),
                        adjustments.unplaced().size());
                announce(event, EInvoiceReturnUnadjusted.Cause.NOT_ON_AN_ISSUED_INVOICE, adjustments.unplaced(),
                        null);
            }
        } catch (BusinessException refused) {
            log.warn("No adjustment e-invoice for return {} of order {}: {}", LogSafe.text(event.rmaNumber()),
                    LogSafe.text(event.orderNumber()), refused.errorCode());
            announce(event, EInvoiceReturnUnadjusted.Cause.REFUSED, refunded(event), refused.errorCode().name());
        }
    }

    private void announce(ReturnResolved event, EInvoiceReturnUnadjusted.Cause cause, Map<UUID, Long> left,
                          String reasonCode) {
        events.publishEvent(new EInvoiceReturnUnadjusted(event.returnRequestId(), event.rmaNumber(), event.orderId(),
                event.orderNumber(), cause, left.size(), left.values().stream().mapToLong(Long::longValue).sum(),
                reasonCode, event.resolvedAt()));
    }

    /** The refunded pieces of the return, by order line: all of them are left when the adjustment is refused. */
    private static Map<UUID, Long> refunded(ReturnResolved event) {
        Map<UUID, Long> pieces = new LinkedHashMap<>();
        for (ReturnResolved.Line line : event.lines()) {
            if (line.creditQuantity() > 0) {
                pieces.merge(line.orderLineId(), (long) line.creditQuantity(), Long::sum);
            }
        }
        return pieces;
    }
}

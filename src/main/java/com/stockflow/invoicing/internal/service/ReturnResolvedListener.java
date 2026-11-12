package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ReturnResolved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

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
 * refusal by the invoice's rules is logged and left, since a retry would be refused again; so are goods no issued
 * invoice holds yet - the accountant adjusts for them once the delivery's invoice is issued
 * ({@code POST /einvoices/{id}/adjustments} with the return's id). Anything else is thrown, so the event stays in the
 * registry and is delivered again.</p>
 */
@Component("invoicingReturnResolvedListener")
class ReturnResolvedListener {

    private static final Logger log = LoggerFactory.getLogger(ReturnResolvedListener.class);

    private final EInvoiceCorrections corrections;

    ReturnResolvedListener(EInvoiceCorrections corrections) {
        this.corrections = corrections;
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
            }
        } catch (BusinessException refused) {
            log.warn("No adjustment e-invoice for return {} of order {}: {}", LogSafe.text(event.rmaNumber()),
                    LogSafe.text(event.orderNumber()), refused.errorCode());
        }
    }
}

package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.payment.internal.domain.CancelledOrderSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * A cancelled order's money (SCRUM-460, kltn-docs 15 §4.4, 17 §4.5): what was still awaited is
 * cancelled, what was received minus any fee kept is asked back. Refunds wait as PENDING for the
 * accountant (15 §2), never paid out by this listener.
 */
@Component("paymentOrderCancelledListener")
class OrderCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledListener.class);

    private final CancelledOrderSettlement settlement;

    OrderCancelledListener(CancelledOrderSettlement settlement) {
        this.settlement = settlement;
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        int cancelled = settlement.cancelAwaitedPayments(event.orderId());
        BigDecimal refundable = event.refundableAmount() == null ? BigDecimal.ZERO : event.refundableAmount();
        if (refundable.signum() > 0) {
            BigDecimal unplaced = settlement.requestRefunds(event.orderId(), refundable, event.currency(),
                    "Order %s cancelled (%s)".formatted(event.orderNumber(), event.reasonCode()));
            if (unplaced.signum() > 0) {
                log.warn("Order {} cancelled with {} {} to refund, but no captured payment carries {} of it; "
                        + "the accountant must settle it by hand", event.orderNumber(), refundable, event.currency(),
                        unplaced);
            }
        }
        log.info("Order {} cancelled: {} awaited payment(s) cancelled, {} {} to refund",
                event.orderNumber(), cancelled, refundable, event.currency());
    }
}

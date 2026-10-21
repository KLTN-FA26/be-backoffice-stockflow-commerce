package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.payment.internal.domain.CancelledOrderSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The rest of a partly fulfilled order was closed (G3-2, split shipments; kltn-docs 17 edge "huỷ + hoàn tiền phần
 * thiếu"): the money received above what the order is now worth is asked back as PENDING refunds for the accountant
 * (15 §4.4), never paid out here. A credit order is billed per delivered shipment, so its closed units are simply never
 * billed; nothing to do for it.
 */
@Component("paymentOrderRemainderClosedListener")
class OrderRemainderClosedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderRemainderClosedListener.class);

    private final CancelledOrderSettlement settlement;
    private final com.stockflow.payment.api.OrderPayables orders;

    OrderRemainderClosedListener(CancelledOrderSettlement settlement, com.stockflow.payment.api.OrderPayables orders) {
        this.settlement = settlement;
        this.orders = orders;
    }

    @ApplicationModuleListener
    public void on(OrderRemainderClosed event) {
        BigDecimal refundable = event.refundableAmount() == null ? BigDecimal.ZERO : event.refundableAmount();
        if (refundable.signum() <= 0) {
            return;
        }
        // G2-3: the order's lock, as a reversal takes it (see OrderCancelledListener).
        orders.lockForPayment(event.orderId());
        BigDecimal unplaced = settlement.requestRemainderRefunds(event.orderId(),
                event.closureId() == null ? event.orderId() : event.closureId(), refundable, event.currency(),
                "Order %s: rest closed (%s)".formatted(event.orderNumber(), event.reasonCode()));
        if (unplaced.signum() > 0) {
            log.warn("Order {}: rest closed with {} {} to refund, but no captured payment carries {} of it; the "
                    + "accountant must settle it by hand", event.orderNumber(), refundable, event.currency(), unplaced);
        }
        log.info("Order {}: rest closed, {} {} to refund", event.orderNumber(), refundable, event.currency());
    }
}

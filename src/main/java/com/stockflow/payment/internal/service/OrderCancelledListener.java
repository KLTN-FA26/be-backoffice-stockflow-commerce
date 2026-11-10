package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.payment.internal.domain.CancelledOrderSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * A cancelled order's money in the money ledger (SCRUM-460, kltn-docs 15 §4.4, 17 §4.5): what was still awaited is
 * cancelled, what was received minus any fee kept is asked back. Refunds wait as PENDING for the
 * accountant (15 §2), never paid out by this listener.
 *
 * <p>A contract order's installments are not this listener's: payment settles them in the cancelling transaction
 * ({@code OrderSettlementService}, 4B-9) - their cancellation, the fee and the amount to refund - so a receipt can never
 * slip between the cancellation and its settlement. The ledger holds no money of a contract order, and the order
 * announces no fee owed for one, so nothing here touches it.</p>
 */
@Component("paymentOrderCancelledListener")
class OrderCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledListener.class);

    private final CancelledOrderSettlement settlement;
    private final com.stockflow.payment.api.OrderPayables orders;
    private final ReceivableService receivables;

    OrderCancelledListener(CancelledOrderSettlement settlement, com.stockflow.payment.api.OrderPayables orders,
                           ReceivableService receivables) {
        this.settlement = settlement;
        this.orders = orders;
        this.receivables = receivables;
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        // G2-3: the order's lock, as an allocation or a reversal takes it - a reversal of the same money waits, then
        // finds the refund (and refuses), or went first and its allocation is skipped here.
        orders.lockForPayment(event.orderId());
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
        receivables.cancellationOwed(event);
        log.info("Order {} cancelled: {} awaited payment(s) cancelled, {} {} to refund",
                event.orderNumber(), cancelled, refundable, event.currency());
    }
}

package com.stockflow.order.internal.service;

import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Moves an order forward, or gives up on it, when payment resolves.
 *
 * <p><b>Why this one is an event when {@code placeOrder} used a direct call.</b> The two
 * situations differ in a way worth being explicit about, because "always call directly" and
 * "always publish an event" are both wrong:</p>
 *
 * <table border="1">
 *   <caption>Choosing between a direct call and an event</caption>
 *   <tr><th></th><th>order → inventory (direct call)</th><th>payment → order (event)</th></tr>
 *   <tr><td>Must it be atomic?</td><td>Yes — an order without its stock is a bug</td>
 *       <td>No — payment succeeded whatever order does next</td></tr>
 *   <tr><td>Does the caller need the answer?</td><td>Yes — it needs the reservation id</td>
 *       <td>No — payment does not care</td></tr>
 *   <tr><td>Direction</td><td>order already depends on inventory</td>
 *       <td>a direct call would make payment depend on order, and order already listens to
 *           payment — a cycle {@code ModularityTest} would reject</td></tr>
 * </table>
 *
 * <p>The rule that falls out: <b>call directly when you need the result inside your transaction;
 * publish an event when you are announcing a fact and do not care who reacts.</b></p>
 */
@Component
class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final OrderServiceImpl orders;

    PaymentEventListener(OrderServiceImpl orders) {
        this.orders = orders;
    }

    @ApplicationModuleListener
    public void on(PaymentCaptured event) {
        orders.markPaid(event.orderId());
        log.info("Order {} marked PAID after payment {} captured {} {}",
                event.orderId(), event.paymentId(), event.amount(), event.currency());
    }

    /**
     * Payment failed: cancel the order.
     *
     * <p>Inventory listens to the same event and releases its own holds. Two independent listeners
     * on one fact, each owning its own reaction — rather than order calling inventory to clean up,
     * which would put knowledge of inventory's cleanup into the order module.</p>
     */
    @ApplicationModuleListener
    public void on(PaymentFailed event) {
        orders.cancelAfterPaymentFailure(event.orderId(), "PAYMENT_FAILED: " + event.failureCode());
        log.info("Order {} cancelled after payment {} failed ({})",
                event.orderId(), event.paymentId(), event.failureMessage());
    }
}

package com.stockflow.order.internal.service;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Moves an order forward when the installments it waits for are paid, and holds it when a voided receipt leaves it
 * short (4B-6). Since 4B-10 these are the only money events: the money ledger's {@code PaymentCaptured} and
 * {@code PaymentReversed} went with it.
 *
 * <p>There is no "payment failed" reaction: customers pay by bank transfer against the contract's instalments
 * (kltn-docs 15 BR-01), so no gateway ever reports a failed payment. The old {@code PaymentFailed} event had no
 * publisher and went with its listeners.</p>
 *
 * <p><b>Why this one is an event when holding an order's stock is a direct call.</b> The two
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
@Component("orderPaymentEventListener")
class PaymentEventListener {

    private final OrderServiceImpl orders;

    PaymentEventListener(OrderServiceImpl orders) {
        this.orders = orders;
    }

    /**
     * 4B-6 (15 BR-07): a receipt paid installments - each order it paid is confirmed once its installments on signing
     * are paid.
     */
    @ApplicationModuleListener
    public void on(com.stockflow.contracts.PaymentReceiptRecorded event) {
        event.lines().stream().map(com.stockflow.contracts.PaymentReceiptRecorded.Line::orderId).distinct().sorted()
                .forEach(orderId -> orders.installmentsPaid(orderId, event.recordedAt()));
    }

    /**
     * 4B-6 (15 BR-13): a receipt voided; when the accountant agreed, a confirmed order it left short of its installment
     * on signing is put on hold for payment.
     */
    @ApplicationModuleListener
    public void on(com.stockflow.contracts.PaymentReceiptVoided event) {
        if (!event.holdOrders()) {
            return;
        }
        event.lines().stream().map(com.stockflow.contracts.PaymentReceiptVoided.Line::orderId).distinct().sorted()
                .forEach(orderId -> orders.installmentsShort(orderId, event.reference(), event.voidedBy(),
                        event.voidedAt()));
    }
}

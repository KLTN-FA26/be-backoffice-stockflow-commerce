package com.stockflow.order.internal.service;

import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentReversed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Moves an order forward when money arrives, and back when the accountant takes it back.
 *
 * <p>There is no "payment failed" reaction: customers pay by bank transfer against the contract's instalments
 * (kltn-docs 15 BR-01), so no gateway ever reports a failed payment. The old {@code PaymentFailed} event had no
 * publisher and went with its listeners.</p>
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
@Component("orderPaymentEventListener")
class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final OrderServiceImpl orders;

    /** A receivable's payment; the fee kind pays a fee a closure left owed (re-review of #150). */
    private static final String FEE_RECEIVABLE_ALLOCATION = "FEE_RECEIVABLE_ALLOCATION";

    private static boolean ofReceivable(String source) {
        return "RECEIVABLE_ALLOCATION".equals(source) || FEE_RECEIVABLE_ALLOCATION.equals(source);
    }

    PaymentEventListener(OrderServiceImpl orders) {
        this.orders = orders;
    }

    /** Money in: counted once per payment; confirms the order when its terms are met (SCRUM-460). */
    @ApplicationModuleListener
    public void on(PaymentCaptured event) {
        // A receivable's payment pays what a delivery billed: the order keeps it apart, so what it still has to bill
        // (credit exposure, G3-2) is not lowered twice.
        orders.recordPayment(event.orderId(), event.paymentId(), event.amount(), event.currency(),
                ofReceivable(event.source()), FEE_RECEIVABLE_ALLOCATION.equals(event.source()));
        log.info("Order {}: payment {} captured {} {}",
                event.orderId(), event.paymentId(), event.amount(), event.currency());
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

    /**
     * Money taken back by the accountant (G2-3, 15 BR-06): counted once per reversal; a confirmed order left short of
     * its terms goes ON_HOLD (reason PAYMENT) rather than silently back to unpaid.
     */
    @ApplicationModuleListener
    public void on(PaymentReversed event) {
        orders.reversePayment(event.orderId(), event.reversalId(), event.amount(), event.currency(),
                ofReceivable(event.source()), FEE_RECEIVABLE_ALLOCATION.equals(event.source()),
                "allocation %s reversed (reversal %s)".formatted(event.allocationId(), event.reversalId()),
                event.reversedBy(), event.reversedAt());
        log.info("Order {}: payment {} reversed, {} {} taken back", event.orderId(), event.allocationId(),
                event.amount(), event.currency());
    }
}

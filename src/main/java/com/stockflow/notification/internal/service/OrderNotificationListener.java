package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.PaymentFailed;
import com.stockflow.contracts.ShipmentDispatched;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Sends the customer-facing messages that follow an order's milestones.
 *
 * <p><b>This class is the payoff for using events at all.</b> Notification depends on nothing —
 * its {@code allowedDependencies} is empty — and no module knows it exists. Adding an SMS on
 * shipment, or a webhook to a partner, means adding a method here; not one line changes in
 * {@code order}, {@code payment} or {@code fulfillment}. Had {@code order} called
 * {@code notificationService.sendConfirmation(...)} directly, every new channel would be an edit
 * to the checkout path, and a mail server outage would fail checkouts.</p>
 *
 * <p><b>Why it is safe to be slow here.</b> {@code @ApplicationModuleListener} runs asynchronously
 * in its own transaction after the publisher commits, so an SMTP timeout delays the email and
 * nothing else. The order is already placed.</p>
 *
 * <p><b>The handler methods are public.</b> {@code @ApplicationModuleListener} is a composition of
 * {@code @Async}, {@code @Transactional(REQUIRES_NEW)} and {@code @TransactionalEventListener}, and
 * the transactional half of that is proxy-based advice, which Spring applies to public methods
 * only. A package-private handler still receives the event and still runs asynchronously — it just
 * runs with no transaction, which is exactly the kind of defect that never shows up until
 * something needs to roll back.</p>
 *
 * <p><b>Failures are not lost.</b> Modulith keeps the {@code event_publication} row until this
 * method returns normally. A mail server that is down when the order is placed leaves the row
 * behind, and {@code republish-outstanding-events-on-restart} sends the email after the next
 * restart. That is durability a plain {@code @EventListener} does not have — an exception there
 * would be logged and the notification silently lost.</p>
 */
@Component
class OrderNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationListener.class);

    private final NotificationSender sender;

    OrderNotificationListener(NotificationSender sender) {
        this.sender = sender;
    }

    @ApplicationModuleListener
    public void on(OrderPlaced event) {
        sender.send(event.customerId(), "order.placed",
                "Order %s received".formatted(event.orderNumber()),
                "We have received order %s for a total of %s %s."
                        .formatted(event.orderNumber(), event.totalAmount(), event.currency()));
        log.debug("Queued order confirmation for {}", event.orderNumber());
    }

    @ApplicationModuleListener
    public void on(PaymentFailed event) {
        sender.send(null, "payment.failed",
                "Payment could not be completed",
                "Payment for your order failed (%s). The items have been released."
                        .formatted(event.failureMessage()));
    }

    @ApplicationModuleListener
    public void on(ShipmentDispatched event) {
        sender.send(null, "shipment.dispatched",
                "Your order is on its way",
                "Tracking number %s with %s.".formatted(event.trackingNumber(), event.carrierCode()));
    }
}

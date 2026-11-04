package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.contracts.ShipmentStatusChanged;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Sends the customer the e-mails that follow its order (SCRUM-352): placed, handed to the carrier, delivered, not
 * delivered, cancelled, part of it closed. What each says is {@link CustomerEmailComposer}'s; how it is sent once and
 * recorded is {@link CustomerEmailDelivery}'s.
 *
 * <p><b>This class is the payoff for using events at all.</b> No module knows it exists. Adding an SMS on shipment, or
 * a webhook to a partner, means adding a method here; not one line changes in {@code order} or {@code fulfillment}.
 * Had {@code order} called notification directly, a mail provider outage would fail checkouts.</p>
 *
 * <h2>{@code @Async @TransactionalEventListener}, not {@code @ApplicationModuleListener}</h2>
 *
 * <p>{@code @ApplicationModuleListener} is the same pair plus {@code @Transactional(REQUIRES_NEW)}, and that last part is
 * what this listener must not have. A transaction around the handler holds a database connection from its first query
 * until it returns - across the call to the mail provider, which may take seconds, on listeners that run on virtual
 * threads with no bound of their own. A busy dispatch would drain the connection pool the checkout needs. So the
 * handler runs outside any transaction and {@link CustomerEmailLog} opens a short one for each read and write.
 * Spring Modulith keeps the event publication for an {@code AFTER_COMMIT} transactional listener either way: it is
 * marked complete when the handler returns, and stays for a retry when it throws.</p>
 *
 * <p><b>The handler methods are public</b>: {@code @Async} is proxy-based advice, applied to public methods only.</p>
 *
 * <p><b>Failures are not lost.</b> A failure worth retrying throws (see {@link CustomerEmailDelivery}); the publication
 * stays in {@code event_publication}, {@link CustomerEmailRetry} dispatches it again once its back-off has passed, and a
 * restart republishes it too. A redelivery of a message already sent changes nothing.</p>
 *
 * <p><b>No e-mail for {@code PaymentFailed}.</b> There was one, saying "your payment failed, the items were released".
 * Nothing publishes {@code PaymentFailed} since the B2B decision (2026-10-06): customers pay by transfer, there is no
 * payment gateway to fail, and an order not paid in time is cancelled - which {@link #on(OrderCancelled)} tells the
 * customer, with the reason. Inventory still listens to {@code PaymentFailed} to release stock; a message nobody can
 * trigger was dead code that looked like a feature.</p>
 */
@Component
class OrderNotificationListener {

    private final CustomerEmailComposer composer;
    private final CustomerEmailDelivery delivery;

    OrderNotificationListener(CustomerEmailComposer composer, CustomerEmailDelivery delivery) {
        this.composer = composer;
        this.delivery = delivery;
    }

    @Async
    @TransactionalEventListener
    public void on(OrderPlaced event) {
        deliver(composer.orderPlaced(event));
    }

    /** kltn-docs 09 §4.1 step 3 and §4.3: handed to the carrier, delivered, not delivered. The rest says nothing. */
    @Async
    @TransactionalEventListener
    public void on(ShipmentStatusChanged event) {
        deliver(composer.shipmentChanged(event));
    }

    /** Only an order delivered without shipments; with shipments each one was told already. */
    @Async
    @TransactionalEventListener
    public void on(OrderDelivered event) {
        deliver(composer.orderDelivered(event));
    }

    @Async
    @TransactionalEventListener
    public void on(OrderCancelled event) {
        deliver(composer.orderCancelled(event));
    }

    @Async
    @TransactionalEventListener
    public void on(OrderRemainderClosed event) {
        deliver(composer.remainderClosed(event));
    }

    private void deliver(Optional<CustomerMessage> message) {
        message.ifPresent(delivery::deliver);
    }
}

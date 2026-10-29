package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ReturnApproved;
import com.stockflow.contracts.ReturnClosed;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnRejected;
import com.stockflow.contracts.ReturnReplacementWithdrawn;
import com.stockflow.contracts.ReturnRequested;
import com.stockflow.contracts.ReturnResolved;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Tells the customer how its return (RMA, SCRUM-243/260) moves: received, approved, refused, goods in, settled,
 * withdrawn or expired. A separate listener from {@link OrderNotificationListener} because the return is its own
 * document with its own events, but the same mechanics for the same reasons: {@code @Async @TransactionalEventListener}
 * without a transaction (no connection held while the provider answers), wording in {@link CustomerEmailComposer},
 * sent once and retried by {@link CustomerEmailDelivery} and {@link CustomerEmailRetry}.
 *
 * <p><b>The handler methods are public</b>: {@code @Async} is proxy-based advice, applied to public methods only.</p>
 */
@Component
class ReturnNotificationListener {

    private final CustomerEmailComposer composer;
    private final CustomerEmailDelivery delivery;

    ReturnNotificationListener(CustomerEmailComposer composer, CustomerEmailDelivery delivery) {
        this.composer = composer;
        this.delivery = delivery;
    }

    @Async
    @TransactionalEventListener
    public void on(ReturnRequested event) {
        deliver(composer.returnRequested(event));
    }

    @Async
    @TransactionalEventListener
    public void on(ReturnApproved event) {
        deliver(composer.returnApproved(event));
    }

    @Async
    @TransactionalEventListener
    public void on(ReturnRejected event) {
        deliver(composer.returnRejected(event));
    }

    @Async
    @TransactionalEventListener
    public void on(ReturnReceived event) {
        deliver(composer.returnReceived(event));
    }

    @Async
    @TransactionalEventListener
    public void on(ReturnResolved event) {
        deliver(composer.returnResolved(event));
    }

    @Async
    @TransactionalEventListener
    public void on(ReturnClosed event) {
        deliver(composer.returnClosed(event));
    }

    @Async
    @TransactionalEventListener
    public void on(ReturnReplacementWithdrawn event) {
        deliver(composer.returnReplacementWithdrawn(event));
    }

    private void deliver(Optional<CustomerMessage> message) {
        message.ifPresent(delivery::deliver);
    }
}

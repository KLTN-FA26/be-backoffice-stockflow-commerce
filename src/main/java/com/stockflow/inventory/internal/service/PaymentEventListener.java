package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.PaymentFailed;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Reacts to payment outcomes by freeing the stock the failed order was holding.
 *
 * <p><b>Why an event and not a direct call.</b> {@code payment} is not in inventory's
 * {@code allowedDependencies} and inventory is not in payment's — deliberately. Payment has no
 * business knowing that stock exists, and a direct call would also mean a failed release rolls
 * back the payment record, which is backwards: the payment genuinely failed and that fact must be
 * recorded whatever inventory does.</p>
 *
 * <p><b>What {@code @ApplicationModuleListener} does</b>, and why it is not a plain
 * {@code @EventListener}. It is the composition of three annotations:</p>
 * <ul>
 *   <li>{@code @Async} — runs off the publisher's thread, so a slow release never delays payment;</li>
 *   <li>{@code @Transactional(propagation = REQUIRES_NEW)} — its own transaction, so a failure here
 *       cannot roll back the payment;</li>
 *   <li>{@code @TransactionalEventListener} — fires only after the publishing transaction commits,
 *       so stock is never freed for a payment failure that got rolled back.</li>
 * </ul>
 *
 * <p><b>Delivery is durable.</b> Modulith writes a row to {@code event_publication} in the
 * publisher's commit and deletes it only when this method returns normally. If the process dies
 * mid-handler the row survives, and
 * {@code spring.modulith.events.republish-outstanding-events-on-restart} redelivers it. That is
 * the guarantee the hand-written outbox table used to provide.</p>
 */
@Component
class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final StockItemRepository repository;
    private final InventoryEventPublisher events;
    private final Clock clock;

    PaymentEventListener(StockItemRepository repository, InventoryEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Payment failed: the order will not ship, so its reservations must go back on sale.
     *
     * <p>Idempotent by construction — {@code releaseReservation} on an already-closed reservation
     * is a no-op. That matters because a redelivery after a crash will hand this method an event
     * it may have partly processed already.</p>
     */
    @ApplicationModuleListener
    public void on(PaymentFailed event) {
        List<StockItem> holders = repository.findWithReservationsForOrder(event.orderId());
        if (holders.isEmpty()) {
            log.debug("Payment {} failed for order {} but no stock was held",
                    event.paymentId(), event.orderId());
            return;
        }

        for (StockItem item : holders) {
            List<ReservationId> toRelease = item.reservations().stream()
                    .filter(r -> r.status().isActive())
                    .filter(r -> r.orderId().equals(event.orderId()))
                    .map(r -> r.id())
                    .toList();
            toRelease.forEach(id ->
                    item.releaseReservation(id, ReleaseReason.PAYMENT_FAILED, clock.instant()));
            repository.save(item);
            events.publishEventsOf(item);
        }

        log.info("Released stock held for order {} after payment {} failed ({})",
                event.orderId(), event.paymentId(), event.failureCode());
    }
}

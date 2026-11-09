package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ProductionOrderSampleApproved;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.contracts.ProductionOrderStopped;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ProductionOrderMail;
import com.stockflow.notification.internal.domain.EmailTransport;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.service.ProductionOrderMessages.Kind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tells a production order's producer what it must know, through the producer's channel (4A-3, kltn-docs 17 §4.3 steps
 * 7, 10, 13; QD-7): the order was sent (its slip and print files), its sample was approved (print the batch), it stopped
 * (cancelled or closed). Each message goes once - a redelivered event or a retry finds it sent - through the delivery log
 * the purchase-order mail uses ({@link RecordedDelivery}); a transient failure is tried again by
 * {@link ProductionOrderMailRetry}.
 *
 * <pre>
 * 1. the production order's lock      every message of one production order, one after the other
 * 2. pending?                          sent already, or settled               -> nothing
 * 3. the production order, read now    gone                                   -> SKIPPED  PRODUCTION_ORDER_NOT_FOUND
 * 4. the producer's channel            the coordinator hands the slip over   -> SKIPPED  PRODUCER_CHANNEL_PDF
 *                                      no address                             -> SKIPPED  RECIPIENT_MISSING
 * 5. stopped since?                    "sent" or "sample approved" not yet out -> FAILED, final  SUPERSEDED_BY_STOP
 *    a stop, when nothing was e-mailed  nothing to stop                          -> SKIPPED  NOTHING_SENT_TO_STOP
 * 6. send                              idempotency key = the message's reference
 * </pre>
 *
 * <p>Why step 1: the stop must never reach the producer before the order it stops. A message being sent holds the
 * production order's lock; the stop waits for it (its event stays incomplete and is retried), and a "sent" still waiting
 * after the stop finds the production order stopped (step 5) and is closed unsent - so whatever the order of the events,
 * the producer never receives an order after being told to stop it.</p>
 *
 * <p>Every attempt at one message writes the same message: its links expire a fixed time after the event it is about,
 * not after the attempt, since a retry under the provider's idempotency key that differed from an attempt the provider
 * had already accepted would be refused as a conflict. A failure to read the production order is recorded as a failed
 * attempt, so the attempt limit applies to it too.</p>
 *
 * <p>Neither the message nor an address is logged; the reference and the outcome are.</p>
 */
@Component
class ProductionOrderMailListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderMailListener.class);

    static final String SUPERSEDED_BY_STOP = "SUPERSEDED_BY_STOP";
    private static final Set<String> STOPPED = Set.of("CANCELLED", "CLOSED");

    private final ProductionOrderDirectory directory;
    private final ProductionOrderMessages messages;
    private final EmailTransport transport;
    private final StaffDirectory staff;
    private final DeliveryLogJpaRepository logs;
    private final RecordedDelivery deliveries;

    ProductionOrderMailListener(ProductionOrderDirectory directory, ProductionOrderMessages messages,
                                EmailTransport transport, StaffDirectory staff, DeliveryLogJpaRepository logs,
                                Clock clock, DeliveryAttemptRecorder attempts) {
        this.directory = directory;
        this.messages = messages;
        this.transport = transport;
        this.staff = staff;
        this.logs = logs;
        this.deliveries = new RecordedDelivery(logs, clock, attempts);
    }

    /** The reference of {@code kind}'s message about a production order: what names it in the delivery log for good. */
    static String reference(UUID productionOrderId, Kind kind) {
        return "production-order:" + productionOrderId + ":" + kind.segment;
    }

    /** Every message's reference about a production order. */
    static List<String> references(UUID productionOrderId) {
        return Arrays.stream(Kind.values()).map(kind -> reference(productionOrderId, kind)).toList();
    }

    @ApplicationModuleListener
    public void on(ProductionOrderSent event) {
        deliver(event.productionOrderId(), Kind.SENT, event.sentAt());
    }

    @ApplicationModuleListener
    public void on(ProductionOrderSampleApproved event) {
        deliver(event.productionOrderId(), Kind.SAMPLE_APPROVED, event.approvedAt());
    }

    @ApplicationModuleListener
    public void on(ProductionOrderStopped event) {
        deliver(event.productionOrderId(), Kind.STOPPED, event.stoppedAt());
    }

    private void deliver(UUID productionOrderId, Kind kind, Instant issuedAt) {
        if (!logs.tryLockDelivery("production-order:" + productionOrderId)) {
            throw new IllegalStateException("Another message about production order " + productionOrderId
                    + " is being sent");
        }
        String reference = reference(productionOrderId, kind);
        if (!deliveries.pending(reference, 0)) {
            return;
        }
        Optional<ProductionOrderMail> found;
        try {
            found = directory.mail(productionOrderId, issuedAt);
        } catch (RuntimeException unreadable) {
            // Recorded, so the attempt limit applies: an order that cannot be read is not retried every minute for ever.
            deliveries.failed(new RecordedDelivery.Delivery(reference, 0, kind.template, NotificationChannel.EMAIL,
                    "-"), unreadable);
            return;
        }
        if (found.isEmpty()) {
            settle(new RecordedDelivery.Delivery(reference, 0, kind.template, NotificationChannel.EMAIL, "-"),
                    "PRODUCTION_ORDER_NOT_FOUND");
            return;
        }
        ProductionOrderMail mail = found.get();
        String recipient = mail.producerEmail() == null ? "producer:" + mail.code() : mail.producerEmail();
        var delivery = new RecordedDelivery.Delivery(reference, 0, kind.template, NotificationChannel.EMAIL, recipient);
        if (!"EMAIL".equals(mail.channel())) {
            settle(delivery, "PRODUCER_CHANNEL_PDF");
            return;
        }
        if (mail.producerEmail() == null) {
            settle(delivery, "RECIPIENT_MISSING");
            return;
        }
        if (kind != Kind.STOPPED && STOPPED.contains(mail.status())) {
            deliveries.close(delivery, SUPERSEDED_BY_STOP);
            log.info("Production order {}: the {} message is not sent, the order stopped", LogSafe.text(mail.code()),
                    kind.segment);
            return;
        }
        if (kind == Kind.STOPPED && !logs.existsByExternalReference(reference(productionOrderId, Kind.SENT))
                && !logs.existsByExternalReference(reference(productionOrderId, Kind.SAMPLE_APPROVED))) {
            // Nothing reached the producer by e-mail (skipped, refused, or closed unsent): there is nothing to stop, and
            // a stop for an order it never received would only confuse it. The coordinator who told it otherwise tells it.
            settle(delivery, "NOTHING_SENT_TO_STOP");
            return;
        }
        String replyTo = staff.activeContact(mail.coordinatorId()).map(StaffContact::email).orElse(null);
        deliveries.send(delivery, () -> transport.send(messages.compose(kind, mail, reference, replyTo)));
        log.info("Production order {}: the {} message is recorded", LogSafe.text(mail.code()), kind.segment);
    }

    private void settle(RecordedDelivery.Delivery delivery, String reason) {
        deliveries.skip(delivery, reason);
        log.info("Production order message {} not sent: {}", delivery.reference(), reason);
    }
}

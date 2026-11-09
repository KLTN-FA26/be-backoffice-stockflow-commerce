package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ProductionOrderSampleApproved;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.contracts.ProductionOrderStopped;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ProductionOrderMail;
import com.stockflow.notification.internal.domain.EmailTransport;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.domain.ProductionOrderMailResend;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.service.ProductionOrderMessages.Kind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
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
 * 2. pending?                          a newer generation was asked for       -> nothing
 *                                      sent already, or settled               -> nothing
 * 3. the production order, read now    gone                                   -> SKIPPED  PRODUCTION_ORDER_NOT_FOUND
 *                                      (a stop reads only its code, status, reason and producer)
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
 * <p><b>Generations.</b> The events of the order module are generation 0 of their message. Staff may send the latest
 * message again ({@link ProductionOrderMailResends}): a new generation, with links fresh from that request, sent under
 * its own reference ({@code <reference>:<generation>}, the provider's idempotency key and the SENT row's
 * {@code external_reference}), since the earlier generation may have gone and must not stop this one.</p>
 *
 * <p><b>Back-off.</b> A failure worth retrying waits {@link #retryAfter} before {@link ProductionOrderMailRetry} tries
 * again - 1 minute, 10 minutes, 1 hour, 6 hours - so the fifth and final failure comes some 7 hours after the first,
 * not 5 minutes: an outage of the mail provider no longer settles a message for good. After that, staff re-send it.</p>
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
        this.deliveries = new RecordedDelivery(logs, clock, attempts, ProductionOrderMailListener::retryAfter);
    }

    /** How long the next attempt waits after the {@code failures}-th failure worth retrying. */
    static Duration retryAfter(long failures) {
        if (failures <= 1) {
            return Duration.ofMinutes(1);
        }
        if (failures == 2) {
            return Duration.ofMinutes(10);
        }
        return failures == 3 ? Duration.ofHours(1) : Duration.ofHours(6);
    }

    /** The lock every message about one production order takes while it is sent, and a re-send while it is asked. */
    static String lockName(UUID productionOrderId) {
        return "production-order:" + productionOrderId;
    }

    /** What the SENT row of a generation of a message carries, and the provider's idempotency key for it. */
    static String sentReference(String reference, int generation) {
        return generation == 0 ? reference : reference + ":" + generation;
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
        deliver(event.productionOrderId(), Kind.SENT, event.sentAt(), 0);
    }

    @ApplicationModuleListener
    public void on(ProductionOrderSampleApproved event) {
        deliver(event.productionOrderId(), Kind.SAMPLE_APPROVED, event.approvedAt(), 0);
    }

    @ApplicationModuleListener
    public void on(ProductionOrderStopped event) {
        deliver(event.productionOrderId(), Kind.STOPPED, event.stoppedAt(), 0);
    }

    @ApplicationModuleListener
    public void on(ProductionOrderMailResend event) {
        deliver(event.productionOrderId(), Kind.valueOf(event.kind()), event.requestedAt(), event.generation());
    }

    private void deliver(UUID productionOrderId, Kind kind, Instant issuedAt, int generation) {
        if (!logs.tryLockDelivery(lockName(productionOrderId))) {
            throw new IllegalStateException("Another message about production order " + productionOrderId
                    + " is being sent");
        }
        String reference = reference(productionOrderId, kind);
        Integer newest = logs.latestGeneration(reference);
        if (newest != null && newest > generation) {
            // Staff asked for it again since: that generation is the message now.
            return;
        }
        String sentReference = sentReference(reference, generation);
        if (!deliveries.pending(reference, generation, sentReference)) {
            return;
        }
        var unknown = new RecordedDelivery.Delivery(reference, generation, kind.template, NotificationChannel.EMAIL,
                "-", sentReference);
        Optional<ProductionOrderMail> found;
        try {
            // A stop needs no line, size or file: reading them could only keep it from going.
            found = kind == Kind.STOPPED ? directory.stop(productionOrderId) : directory.mail(productionOrderId, issuedAt);
        } catch (RuntimeException unreadable) {
            // Recorded, so the attempt limit applies: an order that cannot be read is not retried every minute for ever.
            deliveries.failed(unknown, unreadable);
            return;
        }
        if (found.isEmpty()) {
            settle(unknown, "PRODUCTION_ORDER_NOT_FOUND");
            return;
        }
        ProductionOrderMail mail = found.get();
        String recipient = mail.producerEmail() == null ? "producer:" + mail.code() : mail.producerEmail();
        var delivery = new RecordedDelivery.Delivery(reference, generation, kind.template, NotificationChannel.EMAIL,
                recipient, sentReference);
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
        if (kind == Kind.STOPPED && !reached(productionOrderId, Kind.SENT)
                && !reached(productionOrderId, Kind.SAMPLE_APPROVED)) {
            // Nothing reached the producer by e-mail (skipped, refused, or closed unsent): there is nothing to stop, and
            // a stop for an order it never received would only confuse it. The coordinator who told it otherwise tells it.
            settle(delivery, "NOTHING_SENT_TO_STOP");
            return;
        }
        String replyTo = staff.activeContact(mail.coordinatorId()).map(StaffContact::email).orElse(null);
        deliveries.send(delivery, () -> transport.send(messages.compose(kind, mail, sentReference, replyTo)));
        log.info("Production order {}: the {} message (generation {}) is recorded", LogSafe.text(mail.code()),
                kind.segment, generation);
    }

    /** Whether any generation of {@code kind}'s message reached the producer's mail provider. */
    private boolean reached(UUID productionOrderId, Kind kind) {
        return logs.countByOperationReferenceAndStatus(reference(productionOrderId, kind), DeliveryStatus.SENT) > 0;
    }

    private void settle(RecordedDelivery.Delivery delivery, String reason) {
        deliveries.skip(delivery, reason);
        log.info("Production order message {} not sent: {}", delivery.reference(), reason);
    }
}

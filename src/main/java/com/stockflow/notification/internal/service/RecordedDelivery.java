package com.stockflow.notification.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailReceipt;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;

import java.time.Clock;

/**
 * The shared part of sending a document to a business partner (team plan QD-7): one message per reference and
 * generation, every attempt kept in {@code notification.delivery_log}, a transient failure thrown so the event
 * publication stays incomplete and is tried again ({@link PublicationRedelivery}), a permanent one recorded as final.
 * The purchase-order sender was its first user and is still one; production orders (4A-3) are the second, and the one
 * left when the purchase-order part goes (4A-8).
 *
 * <pre>
 * 1. pending   transaction-scoped advisory lock on the reference (a second delivery of it at the same time throws)
 *              already SENT, or this generation already final?           -> nothing to do
 * 2. the caller's own checks (a newer message replaced this one?)         -> close: one final FAILED row with its code
 *                                                                           skip:  one final SKIPPED row with its reason
 * 3. send      accepted                                                    -> SENT row, external_reference = reference
 *              refused for good (invalid, or the transport says so),
 *              or the fifth failure of the generation                     -> final FAILED row
 *              anything else                                               -> FAILED row, and the failure is rethrown
 * </pre>
 *
 * <p>Not a bean: each listener builds one over the repository, clock and recorder it is given, so the listeners keep
 * the constructors their tests call. Call it inside the listener's transaction, which the advisory lock lasts for.</p>
 */
final class RecordedDelivery {

    /** Failed attempts after which a generation is final; a fifth failure is not retried again. */
    static final int RETRIED_FAILURES = 4;

    /**
     * One message as the log knows it.
     *
     * @param reference    what names the message for good ({@code purchase-order:<id>}, {@code production-order:<id>:sent})
     * @param generation   a deliberate resend is a new generation; a retry of one generation is the same message
     * @param templateCode what kind of message it is, kept on each row
     */
    record Delivery(String reference, int generation, String templateCode, NotificationChannel channel,
                    String recipient) {
    }

    /** The message itself: hands it to the transport and returns its receipt, or null when there is none to keep. */
    @FunctionalInterface
    interface Transmission {
        EmailReceipt send();
    }

    private final DeliveryLogJpaRepository logs;
    private final Clock clock;
    private final DeliveryAttemptRecorder attempts;

    RecordedDelivery(DeliveryLogJpaRepository logs, Clock clock, DeliveryAttemptRecorder attempts) {
        this.logs = logs;
        this.clock = clock;
        this.attempts = attempts;
    }

    /**
     * Locks the message for the caller's transaction and says whether it still has to go: not when it was sent, nor
     * when this generation was settled.
     *
     * @throws IllegalStateException when another delivery of the same message holds the lock; the event stays
     *         incomplete and is tried again
     */
    boolean pending(String reference, int generation) {
        if (!logs.tryLockDelivery(reference)) {
            throw new IllegalStateException("Delivery " + reference + " is already in progress");
        }
        if (logs.existsByExternalReference(reference)) {
            return false;
        }
        return !logs.existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(reference, generation);
    }

    /** Sends the message and records the attempt; see the class comment for what is thrown. */
    void send(Delivery delivery, Transmission transmission) {
        try {
            EmailReceipt receipt = transmission.send();
            var success = new DeliveryLogJpaEntity(Identifiers.newId(), delivery.templateCode(), delivery.channel(),
                    delivery.recipient(), DeliveryStatus.SENT, null, clock.instant(), delivery.reference());
            success.recordGeneration(delivery.generation());
            success.recordAttempt(nextAttempt(delivery));
            if (receipt != null) {
                success.recordProvider(receipt.provider(), receipt.messageId());
            }
            logs.saveAndFlush(success);
        } catch (RuntimeException failure) {
            failed(delivery, failure);
        }
    }

    /**
     * Records a failed attempt at the message - the transport's, or anything before it that kept the message from being
     * written - so the attempt limit applies to it: final when it can never succeed or this was the fifth failure,
     * otherwise rethrown for the retry. The attempt is written in a transaction of its own, so it stands even when the
     * failure rolls the caller's back.
     */
    void failed(Delivery delivery, RuntimeException failure) {
        // A transport that says the message will never be accepted (an invalid address, a revoked key) is not
        // retried four more times: the delivery is final now and waits for a deliberate new generation.
        boolean terminal = failure instanceof IllegalArgumentException
                || failure instanceof EmailDeliveryException refused && !refused.retryable()
                || logs.countByOperationReferenceAndDeliveryGenerationAndStatus(delivery.reference(),
                delivery.generation(), DeliveryStatus.FAILED) >= RETRIED_FAILURES;
        attempts.failed(delivery.recipient(), delivery.channel(), failure, delivery.reference(),
                delivery.templateCode(), terminal, delivery.generation());
        if (!terminal) {
            throw failure;
        }
    }

    /**
     * Closes the message unsent because something newer replaced it ({@code failureCode} says what): one final FAILED
     * attempt - it never reached the partner - so it is not tried again.
     */
    void close(Delivery delivery, String failureCode) {
        logs.saveAndFlush(DeliveryAttemptRecorder.superseded(delivery.reference(), delivery.templateCode(),
                delivery.channel(), delivery.recipient(), failureCode, delivery.generation(), nextAttempt(delivery)));
    }

    /** Settles the message as deliberately not sent, for {@code reason}: final, and not a failure. */
    void skip(Delivery delivery, String reason) {
        var skipped = new DeliveryLogJpaEntity(Identifiers.newId(), delivery.templateCode(), delivery.channel(),
                delivery.recipient(), DeliveryStatus.SKIPPED, reason, null);
        skipped.correlate(delivery.reference());
        skipped.recordGeneration(delivery.generation());
        skipped.recordAttempt(nextAttempt(delivery));
        skipped.stopRetrying();
        logs.saveAndFlush(skipped);
    }

    private int nextAttempt(Delivery delivery) {
        return Math.toIntExact(logs.countByOperationReferenceAndDeliveryGeneration(delivery.reference(),
                delivery.generation()) + 1);
    }
}

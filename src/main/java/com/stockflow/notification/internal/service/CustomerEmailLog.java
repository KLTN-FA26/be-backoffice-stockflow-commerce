package com.stockflow.notification.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.EmailReceipt;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The delivery log of customer messages: what is known about one, and one row per attempt.
 *
 * <p>Each method is its own short transaction, and the delivery around them runs in none: the provider is called
 * between two of these, holding no database connection while it answers (a Resend call can take seconds, and the
 * listeners that send are not bounded in number). Public methods on a separate bean, so the proxy applies them.</p>
 */
@Component
class CustomerEmailLog {

    /**
     * @param sent     a SENT row exists: the message went out
     * @param settled  a terminal row exists: skipped, refused or given up - not tried again
     * @param failures failed attempts so far
     */
    record State(boolean sent, boolean settled, int failures) {
    }

    private final DeliveryLogJpaRepository logs;
    private final Clock clock;

    CustomerEmailLog(DeliveryLogJpaRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public State state(String reference) {
        return new State(logs.existsByExternalReference(reference),
                logs.existsByOperationReferenceAndTerminalTrue(reference),
                Math.toIntExact(logs.countByOperationReferenceAndStatus(reference, DeliveryStatus.FAILED)));
    }

    /** Whether the message's last failure asked for a wait that has not passed yet. */
    @Transactional(readOnly = true)
    public boolean waiting(String reference, Instant now) {
        Instant next = logs.nextAttemptAt(reference);
        return next != null && next.isAfter(now);
    }

    /**
     * The retry is about to hand the message to a send (review of #195, N5): its pending failures wait until
     * {@code until}, so the next run passes it over while that send is still queued behind the provider's rate limit,
     * instead of handing it over a second time. The send's own outcome replaces the lease: a new failure row with its
     * back-off, or a SENT row that completes it. A message with no failure yet has no row to lease and is not
     * retried by this path anyway.
     */
    @Transactional
    public void lease(String reference, Instant until) {
        logs.leaseNextAttempt(reference, until);
    }

    /**
     * The message went out. Its reference is the row's {@code external_reference}, unique: a concurrent delivery of
     * the same message that recorded first makes this insert fail, which the caller reads as "already sent".
     */
    @Transactional
    public void sent(CustomerMessage message, String recipient, EmailReceipt receipt, int attempt) {
        var row = new DeliveryLogJpaEntity(Identifiers.newId(), message.templateCode(), NotificationChannel.EMAIL,
                recipient, DeliveryStatus.SENT, null, clock.instant(), message.reference());
        row.recordProvider(receipt.provider(), receipt.messageId());
        row.recordAttempt(attempt);
        logs.saveAndFlush(row);
    }

    /**
     * An attempt failed. {@code retryAt} null: final, the message is not tried again; otherwise not before then.
     */
    @Transactional
    public void failed(CustomerMessage message, String recipient, String code, int attempt, Instant retryAt) {
        var row = new DeliveryLogJpaEntity(Identifiers.newId(), message.templateCode(), NotificationChannel.EMAIL,
                recipient, DeliveryStatus.FAILED, code, null);
        row.correlate(message.reference());
        row.recordAttempt(attempt);
        if (retryAt == null) {
            row.stopRetrying();
        } else {
            row.retryNotBefore(retryAt);
        }
        logs.saveAndFlush(row);
    }

    /** The message is deliberately not sent, for {@code reason}; final. */
    @Transactional
    public void skipped(CustomerMessage message, String recipient, String reason) {
        var row = new DeliveryLogJpaEntity(Identifiers.newId(), message.templateCode(), NotificationChannel.EMAIL,
                recipient, DeliveryStatus.SKIPPED, reason, null);
        row.correlate(message.reference());
        row.stopRetrying();
        logs.saveAndFlush(row);
    }
}

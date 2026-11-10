package com.stockflow.notification.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

@Component
class DeliveryAttemptRecorder {
    private final DeliveryLogJpaRepository logs;

    DeliveryAttemptRecorder(DeliveryLogJpaRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(
            String recipient,
            NotificationChannel channel,
            RuntimeException failure,
            String reference,
            String templateCode,
            boolean terminal,
            int generation) {
        failed(recipient, channel, failure, reference, templateCode, terminal, generation, null);
    }

    /**
     * {@link #failed(String, NotificationChannel, RuntimeException, String, String, boolean, int)} for a failure worth
     * retrying that waits: the next attempt is not before {@code notBefore} (null: no wait; ignored when terminal).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(
            String recipient,
            NotificationChannel channel,
            RuntimeException failure,
            String reference,
            String templateCode,
            boolean terminal,
            int generation,
            java.time.Instant notBefore) {
        String message = failureCode(channel, failure);
        var attempt =
                new DeliveryLogJpaEntity(
                        Identifiers.newId(),
                        templateCode,
                        channel,
                        recipient,
                        DeliveryStatus.FAILED,
                        message,
                        null);
        attempt.correlate(reference);
        attempt.recordGeneration(generation);
        attempt.recordAttempt(
                Math.toIntExact(
                        logs.countByOperationReferenceAndDeliveryGeneration(reference, generation)
                                + 1));
        if (terminal) attempt.stopRetrying();
        else if (notBefore != null) attempt.retryNotBefore(notBefore);
        logs.saveAndFlush(attempt);
        LoggerFactory.getLogger(DeliveryAttemptRecorder.class)
                .error("Partner notification failed: {} ({})", reference, message, failure);
    }

    /**
     * A terminal FAILED attempt for a delivery that is closed without being sent because something newer replaced it
     * ({@code failureCode} says what): it never reached the partner, and terminal is what stops it being retried.
     */
    static DeliveryLogJpaEntity superseded(
            String reference,
            String templateCode,
            NotificationChannel channel,
            String recipient,
            String failureCode,
            int generation,
            int attempt) {
        var closing =
                new DeliveryLogJpaEntity(
                        Identifiers.newId(),
                        templateCode,
                        channel,
                        recipient,
                        DeliveryStatus.FAILED,
                        failureCode,
                        null);
        closing.correlate(reference);
        closing.recordGeneration(generation);
        closing.recordAttempt(attempt);
        closing.stopRetrying();
        return closing;
    }

    static String failureCode(NotificationChannel channel, RuntimeException failure) {
        if (failure instanceof IllegalArgumentException) return "DELIVERY_INVALID";
        if (channel == NotificationChannel.EMAIL) return "MAIL_SEND_FAILED";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException)
                return "API_TIMEOUT";
            if (cause instanceof RestClientResponseException) return "API_REJECTED";
            if (cause.getCause() == cause) break;
        }
        return "API_SEND_FAILED";
    }
}

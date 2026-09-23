package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
class DeliveryAttemptRecorder {
    private final DeliveryLogJpaRepository logs;
    private final Clock clock;

    DeliveryAttemptRecorder(DeliveryLogJpaRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String recipient, NotificationChannel channel, RuntimeException failure, String reference) {
        String message = reference + ": " + failure.getClass().getSimpleName();
        var attempt = new DeliveryLogJpaEntity(com.stockflow.common.id.Identifiers.newId(), "purchase-order.sent", channel,
                recipient, DeliveryStatus.FAILED, message, null);
        attempt.correlate(reference);
        logs.saveAndFlush(attempt);
        org.slf4j.LoggerFactory.getLogger(DeliveryAttemptRecorder.class)
                .error("Supplier notification failed: {} ({})", reference, failure.getClass().getSimpleName());
    }
}

package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Component
class DeliveryAttemptRecorder {
    private final DeliveryLogJpaRepository logs;
    private final Clock clock;

    DeliveryAttemptRecorder(DeliveryLogJpaRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String recipient, NotificationChannel channel, RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        logs.save(new DeliveryLogJpaEntity(UUID.randomUUID(), "purchase-order.sent", channel,
                recipient, DeliveryStatus.FAILED, message.substring(0, Math.min(1000, message.length())),
                clock.instant()));
    }
}

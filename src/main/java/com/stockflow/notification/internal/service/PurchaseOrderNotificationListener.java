package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
class PurchaseOrderNotificationListener {
    private final NotificationSender sender;
    private final DeliveryLogJpaRepository logs;
    private final Clock clock;
    private final DeliveryAttemptRecorder attempts;

    PurchaseOrderNotificationListener(NotificationSender sender,
                                      DeliveryLogJpaRepository logs, Clock clock, DeliveryAttemptRecorder attempts) {
        this.sender = sender;
        this.logs = logs;
        this.clock = clock;
        this.attempts = attempts;
    }

    @ApplicationModuleListener
    public void on(PurchaseOrderSent event) {
        String reference = "purchase-order:" + event.purchaseOrderId();
        if (!logs.tryLockDelivery(reference)) {
            throw new IllegalStateException("Purchase order delivery is already in progress");
        }
        if (logs.existsByExternalReference(reference)) return;
        NotificationChannel channel = NotificationChannel.valueOf(event.channel());
        try {
            sender.sendPurchaseOrder(event);
            logs.saveAndFlush(new DeliveryLogJpaEntity(com.stockflow.common.id.Identifiers.newId(), "purchase-order.sent", channel,
                    event.recipient(), DeliveryStatus.SENT, null, clock.instant(), reference));
        } catch (RuntimeException failure) {
            attempts.failed(event.recipient(), channel, failure, reference);
            throw failure;
        }
    }

}

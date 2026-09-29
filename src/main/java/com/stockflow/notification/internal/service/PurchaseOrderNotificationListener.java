package com.stockflow.notification.internal.service;

import com.stockflow.common.id.Identifiers;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.repository.PoDeliveryControlRepository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
class PurchaseOrderNotificationListener {
    private final NotificationSender sender;
    private final DeliveryLogJpaRepository logs;
    private final Clock clock;
    private final DeliveryAttemptRecorder attempts;
    private final PoDeliveryControlRepository controls;

    PurchaseOrderNotificationListener(NotificationSender sender,
                                      DeliveryLogJpaRepository logs, Clock clock, DeliveryAttemptRecorder attempts,
                                      PoDeliveryControlRepository controls) {
        this.sender = sender;
        this.logs = logs;
        this.clock = clock;
        this.attempts = attempts;
        this.controls = controls;
    }

    @ApplicationModuleListener
    public void on(PurchaseOrderSent event) {
        var control = controls.lock(event.purchaseOrderId());
        if (control.suppressed() || control.generation() != event.deliveryGeneration()) return;
        String reference = "purchase-order:" + event.purchaseOrderId();
        if (!logs.tryLockDelivery(reference)) {
            throw new IllegalStateException("Purchase order delivery is already in progress");
        }
        if (logs.existsByExternalReference(reference)) return;
        if (logs.existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(reference, event.deliveryGeneration())) return;
        NotificationChannel channel = NotificationChannel.valueOf(event.channel());
        try {
            sender.sendPurchaseOrder(event);
            var success = new DeliveryLogJpaEntity(Identifiers.newId(), "purchase-order.sent", channel,
                    event.recipient(), DeliveryStatus.SENT, null, clock.instant(), reference);
            success.recordGeneration(event.deliveryGeneration());
            logs.saveAndFlush(success);
        } catch (RuntimeException failure) {
            boolean terminal = failure instanceof IllegalArgumentException
                    || logs.countByOperationReferenceAndDeliveryGenerationAndStatus(reference, event.deliveryGeneration(), DeliveryStatus.FAILED) >= 4;
            attempts.failed(event.recipient(), channel, failure, reference, terminal, event.deliveryGeneration());
            if (!terminal) throw failure;
        }
    }

}

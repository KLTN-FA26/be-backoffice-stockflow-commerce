package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.repository.PoDeliveryControlRepository;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Delivers a {@link PurchaseOrderSent}. The order itself is skipped when its delivery was suppressed (the supplier
 * answered meanwhile) or a newer recovery generation replaced it; a cancellation and an amendment are not
 * (SCRUM-117): each says something the supplier must still be told after answering, and each has a delivery reference
 * of its own, so the order's "already sent" never swallows it.
 *
 * <p>An amendment notice is closed unsent instead - one terminal FAILED attempt, so it is not retried - once it is
 * stale: a notice of a later revision was attempted, or the order was cancelled. Each notice carries the whole order as
 * it stood, so an earlier one still retrying would otherwise reach the supplier after the later revision or the
 * cancellation and undo it in their eyes. Decided under the order's delivery lock, which every notice of the order
 * holds while it is sent: a later notice not attempted yet goes out after this one, which keeps the order right.</p>
 *
 * <p>Locking, the attempt log and the retry decision are {@link RecordedDelivery}'s, shared with production orders
 * (QD-7); what stays here is what only a purchase order has: the delivery control and its generations, and the
 * staleness of amendment notices.</p>
 */
@Component
class PurchaseOrderNotificationListener {
    private final NotificationSender sender;
    private final DeliveryLogJpaRepository logs;
    private final PoDeliveryControlRepository controls;
    private final RecordedDelivery deliveries;

    PurchaseOrderNotificationListener(
            NotificationSender sender,
            DeliveryLogJpaRepository logs,
            Clock clock,
            DeliveryAttemptRecorder attempts,
            PoDeliveryControlRepository controls) {
        this.sender = sender;
        this.logs = logs;
        this.controls = controls;
        this.deliveries = new RecordedDelivery(logs, clock, attempts);
    }

    @ApplicationModuleListener
    public void on(PurchaseOrderSent event) {
        var control = controls.lock(event.purchaseOrderId());
        if (!event.cancellation()
                && !event.amendment()
                && (control.suppressed() || control.generation() != event.deliveryGeneration()))
            return;
        String reference = event.operationReference();
        if (!deliveries.pending(reference, event.deliveryGeneration())) return;
        var delivery = new RecordedDelivery.Delivery(reference, event.deliveryGeneration(), event.templateCode(),
                NotificationChannel.valueOf(event.channel()), event.recipient());
        if (event.amendment()
                && (control.cancellationRequested()
                        || logs.existsLaterAmendmentNotice(
                                event.purchaseOrderId().toString(), event.amendmentRevisionNo()))) {
            deliveries.close(delivery, control.cancellationRequested()
                    ? NotificationServiceImpl.SUPERSEDED_BY_CANCELLATION
                    : NotificationServiceImpl.SUPERSEDED_BY_AMENDMENT);
            return;
        }
        deliveries.send(delivery, () -> {
            sender.sendPurchaseOrder(event);
            return null;
        });
    }
}

package com.stockflow.notification.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
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
 */
@Component
class PurchaseOrderNotificationListener {
    private final NotificationSender sender;
    private final DeliveryLogJpaRepository logs;
    private final Clock clock;
    private final DeliveryAttemptRecorder attempts;
    private final PoDeliveryControlRepository controls;

    PurchaseOrderNotificationListener(
            NotificationSender sender,
            DeliveryLogJpaRepository logs,
            Clock clock,
            DeliveryAttemptRecorder attempts,
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
        if (!event.cancellation()
                && !event.amendment()
                && (control.suppressed() || control.generation() != event.deliveryGeneration()))
            return;
        String reference = event.operationReference();
        if (!logs.tryLockDelivery(reference)) {
            throw new IllegalStateException("Purchase order delivery is already in progress");
        }
        if (logs.existsByExternalReference(reference)) return;
        if (logs.existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(
                reference, event.deliveryGeneration())) return;
        NotificationChannel channel = NotificationChannel.valueOf(event.channel());
        if (event.amendment()
                && (control.cancellationRequested()
                        || logs.existsLaterAmendmentNotice(
                                event.purchaseOrderId().toString(), event.amendmentRevisionNo()))) {
            logs.saveAndFlush(
                    DeliveryAttemptRecorder.superseded(
                            reference,
                            event.templateCode(),
                            channel,
                            event.recipient(),
                            control.cancellationRequested()
                                    ? NotificationServiceImpl.SUPERSEDED_BY_CANCELLATION
                                    : NotificationServiceImpl.SUPERSEDED_BY_AMENDMENT,
                            event.deliveryGeneration(),
                            Math.toIntExact(
                                    logs.countByOperationReferenceAndDeliveryGeneration(
                                                    reference, event.deliveryGeneration())
                                            + 1)));
            return;
        }
        try {
            sender.sendPurchaseOrder(event);
            var success =
                    new DeliveryLogJpaEntity(
                            Identifiers.newId(),
                            event.templateCode(),
                            channel,
                            event.recipient(),
                            DeliveryStatus.SENT,
                            null,
                            clock.instant(),
                            reference);
            success.recordGeneration(event.deliveryGeneration());
            success.recordAttempt(
                    Math.toIntExact(
                            logs.countByOperationReferenceAndDeliveryGeneration(
                                            reference, event.deliveryGeneration())
                                    + 1));
            logs.saveAndFlush(success);
        } catch (RuntimeException failure) {
            // A transport that says the message will never be accepted (an invalid address, a revoked key) is not
            // retried four more times: the delivery is terminal now and waits for an operator's recovery generation.
            boolean terminal =
                    failure instanceof IllegalArgumentException
                            || failure instanceof EmailDeliveryException refused && !refused.retryable()
                            || logs.countByOperationReferenceAndDeliveryGenerationAndStatus(
                                            reference,
                                            event.deliveryGeneration(),
                                            DeliveryStatus.FAILED)
                                    >= 4;
            attempts.failed(
                    event.recipient(),
                    channel,
                    failure,
                    reference,
                    event.templateCode(),
                    terminal,
                    event.deliveryGeneration());
            if (!terminal) throw failure;
        }
    }
}

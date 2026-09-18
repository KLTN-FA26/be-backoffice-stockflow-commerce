package com.stockflow.notification.internal.service;

import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;
import java.util.UUID;

@Component
class PurchaseOrderNotificationListener {
    private final JavaMailSender mailSender;
    private final RestClientFactory clients;
    private final DeliveryLogJpaRepository logs;
    private final Clock clock;
    private final DeliveryAttemptRecorder attempts;
    private final String mailFrom;

    PurchaseOrderNotificationListener(JavaMailSender mailSender, RestClientFactory clients,
                                      DeliveryLogJpaRepository logs, Clock clock, DeliveryAttemptRecorder attempts,
                                      @Value("${stockflow.notification.mail-from:noreply@stockflow.local}") String mailFrom) {
        this.mailSender = mailSender;
        this.clients = clients;
        this.logs = logs;
        this.clock = clock;
        this.attempts = attempts;
        this.mailFrom = mailFrom;
    }

    @ApplicationModuleListener
    public void on(PurchaseOrderSent event) {
        String reference = "purchase-order:" + event.purchaseOrderId();
        if (logs.existsByExternalReference(reference)) return;
        NotificationChannel channel = NotificationChannel.valueOf(event.channel());
        try {
            if (channel == NotificationChannel.EMAIL) sendEmail(event); else sendApi(event);
            logs.save(new DeliveryLogJpaEntity(UUID.randomUUID(), "purchase-order.sent", channel,
                    event.recipient(), DeliveryStatus.SENT, null, clock.instant(), reference));
        } catch (RuntimeException failure) {
            attempts.failed(event.recipient(), channel, failure);
            throw failure;
        }
    }

    private void sendEmail(PurchaseOrderSent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(event.recipient());
        message.setSubject("Purchase order " + event.poNumber());
        message.setText("Purchase order %s totals %s %s, expected %s. Payment term: %d days."
                .formatted(event.poNumber(), event.totalAmount(), event.currency(), event.expectedAt(), event.paymentTermDays()));
        mailSender.send(message);
    }

    private void sendApi(PurchaseOrderSent event) {
        clients.forService("supplier-po-api", event.recipient()).build().post()
                .body(event).retrieve().toBodilessEntity();
    }
}

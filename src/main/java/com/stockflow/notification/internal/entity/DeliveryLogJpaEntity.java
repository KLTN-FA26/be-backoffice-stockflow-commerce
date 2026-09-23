package com.stockflow.notification.internal.entity;

import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a delivery-log row (table {@code notification.delivery_log}). STARTER ENTITY. */
@Entity
@Table(name = "delivery_log", schema = "notification")
public class DeliveryLogJpaEntity extends BaseEntity {

    @Column(name = "template_code", length = 64)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "recipient", nullable = false, length = 500)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryStatus status;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "operation_reference", length = 100)
    private String operationReference;

    protected DeliveryLogJpaEntity() {
    }

    public DeliveryLogJpaEntity(UUID id, String templateCode, NotificationChannel channel,
                                String recipient, DeliveryStatus status, String error, Instant sentAt) {
        this(id, templateCode, channel, recipient, status, error, sentAt, null);
    }

    public DeliveryLogJpaEntity(UUID id, String templateCode, NotificationChannel channel,
                                String recipient, DeliveryStatus status, String error, Instant sentAt,
                                String externalReference) {
        super(id);
        this.templateCode = templateCode;
        this.channel = channel;
        this.recipient = recipient;
        this.status = status;
        this.error = error;
        this.sentAt = sentAt;
        this.externalReference = externalReference;
        this.operationReference = externalReference;
    }

    public String getTemplateCode() { return templateCode; }
    public NotificationChannel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public DeliveryStatus getStatus() { return status; }
    public String getError() { return error; }
    public Instant getSentAt() { return sentAt; }
    public String getExternalReference() { return externalReference; }
    public void correlate(String reference) { this.operationReference = reference; }
}

package com.stockflow.notification.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.domain.ProviderStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a delivery-log row (table {@code notification.delivery_log}): one row per attempt to deliver one
 * message. Supplier purchase orders and, since SCRUM-352, customer e-mail write here; the guards on the provider
 * columns repeat the table's CHECK constraints so a mistake fails here with a message rather than at flush.
 */
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

    @Column(name = "terminal", nullable = false)
    private boolean terminal;

    @Column(name = "delivery_generation", nullable = false)
    private int deliveryGeneration;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber = 1;

    /** Which transport took the message; null on rows written before SCRUM-352 and on skips. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 16)
    private EmailProvider provider;

    /** The provider's id of a sent message: what a delivery webhook names. */
    @Column(name = "provider_message_id", length = 100)
    private String providerMessageId;

    /** A failed attempt that will be retried is not retried before this. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_status", length = 24)
    private ProviderStatus providerStatus;

    @Column(name = "provider_status_at")
    private Instant providerStatusAt;

    protected DeliveryLogJpaEntity() {}

    public DeliveryLogJpaEntity(
            UUID id,
            String templateCode,
            NotificationChannel channel,
            String recipient,
            DeliveryStatus status,
            String error,
            Instant sentAt) {
        this(id, templateCode, channel, recipient, status, error, sentAt, null);
    }

    public DeliveryLogJpaEntity(
            UUID id,
            String templateCode,
            NotificationChannel channel,
            String recipient,
            DeliveryStatus status,
            String error,
            Instant sentAt,
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

    public String getTemplateCode() {
        return templateCode;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getRecipient() {
        return recipient;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getOperationReference() {
        return operationReference;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void recordAttempt(int number) {
        this.attemptNumber = number;
    }

    public int getDeliveryGeneration() {
        return deliveryGeneration;
    }

    public void correlate(String reference) {
        this.operationReference = reference;
    }

    /** Final: nothing retries this delivery again, so it waits for nothing (ck_delivery_log_next_attempt). */
    public void stopRetrying() {
        this.terminal = true;
        this.nextAttemptAt = null;
    }

    public void recordGeneration(int generation) {
        this.deliveryGeneration = generation;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public EmailProvider getProvider() {
        return provider;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public ProviderStatus getProviderStatus() {
        return providerStatus;
    }

    public Instant getProviderStatusAt() {
        return providerStatusAt;
    }

    /**
     * The transport that took the message, and its id for it. An id only on a message the provider accepted
     * (ck_delivery_log_provider_message): a webhook names accepted messages only.
     */
    public void recordProvider(EmailProvider provider, String messageId) {
        if (messageId != null && (status != DeliveryStatus.SENT || provider == null)) {
            throw new IllegalStateException("Only a sent message carries the provider's id");
        }
        this.provider = provider;
        this.providerMessageId = messageId;
    }

    /** A failure worth retrying, not before {@code at} (ck_delivery_log_next_attempt). */
    public void retryNotBefore(Instant at) {
        if (status != DeliveryStatus.FAILED || terminal) {
            throw new IllegalStateException("Only a failed attempt that will be retried waits for a time");
        }
        this.nextAttemptAt = at;
    }

    /**
     * A report from the provider about the message it accepted; kept only when it says more than the one stored
     * ({@link ProviderStatus#replaces}). Webhooks are retried and arrive out of order, so a stale or repeated report
     * changes nothing.
     *
     * @return whether the stored report changed
     */
    public boolean reportProviderStatus(ProviderStatus reported, Instant at) {
        if (providerMessageId == null) {
            throw new IllegalStateException("Only a message the provider accepted can be reported on");
        }
        java.util.Objects.requireNonNull(reported, "reported");
        java.util.Objects.requireNonNull(at, "at");
        if (!reported.replaces(providerStatus, providerStatusAt, at)) {
            return false;
        }
        this.providerStatus = reported;
        this.providerStatusAt = at;
        return true;
    }
}

package com.stockflow.chat.internal.entity;

import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of {@code chat.conversation}. Not the domain model: {@code ConversationRepositoryAdapter} copies a
 * {@code Conversation.Snapshot} in and out. The columns that never change after the insert are not updatable, as the
 * lifecycle trigger requires.
 */
@Entity
@Table(name = "conversation", schema = "chat")
public class ConversationJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "subject", length = 300, updatable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConversationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "opened_by_type", nullable = false, length = 16, updatable = false)
    private SenderType openedByType;

    @Column(name = "opened_by", updatable = false)
    private UUID openedBy;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "first_response_due_at")
    private Instant firstResponseDueAt;

    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;

    @Column(name = "first_response_alerted_at")
    private Instant firstResponseAlertedAt;

    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    @Column(name = "resolution_alerted_at")
    private Instant resolutionAlertedAt;

    @Column(name = "escalation_reason", length = 1000)
    private String escalationReason;

    @Column(name = "escalated_to_id")
    private UUID escalatedToId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "auto_closed_at")
    private Instant autoClosedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "reopen_until")
    private Instant reopenUntil;

    @Column(name = "last_customer_message_at")
    private Instant lastCustomerMessageAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "last_message_seq", nullable = false)
    private long lastMessageSeq;

    @Column(name = "csat_score")
    private Short csatScore;

    @Column(name = "csat_comment", length = 2000)
    private String csatComment;

    @Column(name = "csat_submitted_at")
    private Instant csatSubmittedAt;

    @Column(name = "continued_from_id", updatable = false)
    private UUID continuedFromId;

    @Column(name = "merged_into_id")
    private UUID mergedIntoId;

    protected ConversationJpaEntity() {
    }

    public ConversationJpaEntity(UUID id, UUID customerId, UUID orderId, String subject, SenderType openedByType,
                                 UUID openedBy, UUID continuedFromId) {
        super(id);
        this.customerId = customerId;
        this.orderId = orderId;
        this.subject = subject;
        this.openedByType = openedByType;
        this.openedBy = openedBy;
        this.continuedFromId = continuedFromId;
    }

    /** Copies the aggregate's changeable state in. */
    public void apply(ConversationStatus status, UUID assignedAgentId, Instant assignedAt, Instant statusChangedAt,
                      Instant firstResponseDueAt, Instant firstRespondedAt, Instant firstResponseAlertedAt,
                      Instant resolutionDueAt, Instant resolutionAlertedAt, String escalationReason,
                      UUID escalatedToId, Instant resolvedAt, UUID resolvedBy, Instant autoClosedAt, Instant closedAt,
                      Instant reopenUntil, Instant lastCustomerMessageAt, Instant lastMessageAt, long lastMessageSeq,
                      Integer csatScore, String csatComment, Instant csatSubmittedAt, UUID mergedIntoId) {
        this.status = status;
        this.assignedAgentId = assignedAgentId;
        this.assignedAt = assignedAt;
        this.statusChangedAt = statusChangedAt;
        this.firstResponseDueAt = firstResponseDueAt;
        this.firstRespondedAt = firstRespondedAt;
        this.firstResponseAlertedAt = firstResponseAlertedAt;
        this.resolutionDueAt = resolutionDueAt;
        this.resolutionAlertedAt = resolutionAlertedAt;
        this.escalationReason = escalationReason;
        this.escalatedToId = escalatedToId;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = resolvedBy;
        this.autoClosedAt = autoClosedAt;
        this.closedAt = closedAt;
        this.reopenUntil = reopenUntil;
        this.lastCustomerMessageAt = lastCustomerMessageAt;
        this.lastMessageAt = lastMessageAt;
        this.lastMessageSeq = lastMessageSeq;
        this.csatScore = csatScore == null ? null : csatScore.shortValue();
        this.csatComment = csatComment;
        this.csatSubmittedAt = csatSubmittedAt;
        this.mergedIntoId = mergedIntoId;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getOrderId() { return orderId; }
    public String getSubject() { return subject; }
    public ConversationStatus getStatus() { return status; }
    public SenderType getOpenedByType() { return openedByType; }
    public UUID getOpenedBy() { return openedBy; }
    public UUID getAssignedAgentId() { return assignedAgentId; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
    public Instant getFirstResponseDueAt() { return firstResponseDueAt; }
    public Instant getFirstRespondedAt() { return firstRespondedAt; }
    public Instant getFirstResponseAlertedAt() { return firstResponseAlertedAt; }
    public Instant getResolutionDueAt() { return resolutionDueAt; }
    public Instant getResolutionAlertedAt() { return resolutionAlertedAt; }
    public String getEscalationReason() { return escalationReason; }
    public UUID getEscalatedToId() { return escalatedToId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getResolvedBy() { return resolvedBy; }
    public Instant getAutoClosedAt() { return autoClosedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getReopenUntil() { return reopenUntil; }
    public Instant getLastCustomerMessageAt() { return lastCustomerMessageAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public long getLastMessageSeq() { return lastMessageSeq; }
    public Short getCsatScore() { return csatScore; }
    public String getCsatComment() { return csatComment; }
    public Instant getCsatSubmittedAt() { return csatSubmittedAt; }
    public UUID getContinuedFromId() { return continuedFromId; }
    public UUID getMergedIntoId() { return mergedIntoId; }
}

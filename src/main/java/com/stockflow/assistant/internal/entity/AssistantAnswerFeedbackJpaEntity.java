package com.stockflow.assistant.internal.entity;

import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.security.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * {@code assistant.answer_feedback}: one row per answer, rewritten when its owner changes their mind; the message it
 * rates stays append-only. Read with scope OWN, like the conversation, so nobody reaches another person's verdicts.
 */
@Entity
@Table(name = "answer_feedback", schema = "assistant")
public class AssistantAnswerFeedbackJpaEntity extends BaseEntity implements ScopedEntity {

    /** Read only for its attribute names by {@link ScopedEntity}; never persisted, never returned. */
    public static final AssistantAnswerFeedbackJpaEntity SCOPE_PROTOTYPE = new AssistantAnswerFeedbackJpaEntity();

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 8)
    private FeedbackVerdict verdict;

    @Column(name = "reason", length = 500)
    private String reason;

    protected AssistantAnswerFeedbackJpaEntity() {
    }

    public AssistantAnswerFeedbackJpaEntity(UUID id, UUID messageId, UUID ownerUserId) {
        super(id);
        this.messageId = messageId;
        this.ownerUserId = ownerUserId;
    }

    @Override
    public String ownerAttribute() {
        return "ownerUserId";
    }

    /** A verdict is a person's, never a warehouse's. */
    @Override
    public String warehouseAttribute() {
        return null;
    }

    /** The verdict given now, in place of any earlier one. */
    public void give(FeedbackVerdict verdict, String reason) {
        this.verdict = verdict;
        this.reason = reason;
    }

    public UUID getMessageId() { return messageId; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public FeedbackVerdict getVerdict() { return verdict; }
    public String getReason() { return reason; }
}

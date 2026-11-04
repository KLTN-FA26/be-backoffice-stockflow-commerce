package com.stockflow.assistant.internal.entity;

import com.stockflow.assistant.internal.domain.AssistantAction;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** {@code assistant.action}: a suggestion of the assistant and its owner's decision on it (ADR-0011). */
@Entity
@Table(name = "action", schema = "assistant")
public class AssistantActionJpaEntity extends BaseEntity {

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32, updatable = false)
    private AssistantAction.Kind kind;

    @Column(name = "proposal_key", nullable = false, length = 200, updatable = false)
    private String proposalKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String suggested;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submitted", columnDefinition = "jsonb")
    private String submitted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssistantAction.Status status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** The document, in the column of the suggestion's kind ({@code ck_action_result}). */
    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "goods_receipt_id")
    private UUID goodsReceiptId;

    @Column(name = "result_number", length = 64)
    private String resultNumber;

    protected AssistantActionJpaEntity() {
    }

    public AssistantActionJpaEntity(AssistantAction action) {
        super(action.id());
        this.ownerUserId = action.ownerUserId();
        this.conversationId = action.conversationId();
        this.messageId = action.messageId();
        this.kind = action.kind();
        this.proposalKey = action.proposalKey();
        this.suggested = action.suggested();
        this.expiresAt = action.expiresAt();
        decide(action);
    }

    /** What may change after the insert: the decision. */
    public void decide(AssistantAction action) {
        this.status = action.storedStatus();
        this.submitted = action.submitted();
        this.decidedAt = action.decidedAt();
        this.purchaseOrderId = kind == AssistantAction.Kind.PURCHASE_ORDER_DRAFT ? action.resultId() : null;
        this.goodsReceiptId = kind == AssistantAction.Kind.GOODS_RECEIPT_DRAFT ? action.resultId() : null;
        this.resultNumber = action.resultNumber();
    }

    public AssistantAction toDomain() {
        return new AssistantAction(getId(), ownerUserId, conversationId, messageId, kind, proposalKey, suggested,
                status, submitted, getCreatedAt(), expiresAt, decidedAt,
                kind == AssistantAction.Kind.PURCHASE_ORDER_DRAFT ? purchaseOrderId : goodsReceiptId, resultNumber);
    }

    public UUID getOwnerUserId() { return ownerUserId; }
    public UUID getMessageId() { return messageId; }
    public AssistantAction.Status getStatus() { return status; }
}

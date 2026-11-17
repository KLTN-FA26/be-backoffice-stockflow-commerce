package com.stockflow.chat.internal.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.chat.internal.domain.ConversationEventType;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of {@code chat.conversation_event}, the log of 16 BR-01. {@link Immutable}: Hibernate never issues an
 * update for it, which is what the append-only trigger would refuse anyway.
 */
@Entity
@Immutable
@Table(name = "conversation_event", schema = "chat")
public class ConversationEventJpaEntity extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48)
    private ConversationEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private SenderType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ConversationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32)
    private ConversationStatus toStatus;

    @Column(name = "context_id")
    private UUID contextId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(name = "note", length = 1000)
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private JsonNode detail;

    protected ConversationEventJpaEntity() {
    }

    public ConversationEventJpaEntity(UUID id, UUID conversationId, ConversationEventType type, SenderType actorType,
                                      UUID actorId, Instant occurredAt, ConversationStatus fromStatus,
                                      ConversationStatus toStatus, UUID contextId, UUID messageId, UUID targetUserId,
                                      String note, JsonNode detail) {
        super(id);
        this.conversationId = conversationId;
        this.type = type;
        this.actorType = actorType;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.contextId = contextId;
        this.messageId = messageId;
        this.targetUserId = targetUserId;
        this.note = note;
        this.detail = detail;
    }

    public UUID getConversationId() { return conversationId; }
    public ConversationEventType getType() { return type; }
    public SenderType getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
    public ConversationStatus getFromStatus() { return fromStatus; }
    public ConversationStatus getToStatus() { return toStatus; }
    public UUID getContextId() { return contextId; }
    public UUID getMessageId() { return messageId; }
    public UUID getTargetUserId() { return targetUserId; }
    public String getNote() { return note; }
    public JsonNode getDetail() { return detail; }
}

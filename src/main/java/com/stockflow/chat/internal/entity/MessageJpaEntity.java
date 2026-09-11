package com.stockflow.chat.internal.entity;

import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a message (table {@code chat.message}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "message", schema = "chat")
public class MessageJpaEntity extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 32)
    private SenderType senderType;

    @Column(name = "sender_id")
    private UUID senderId;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected MessageJpaEntity() {
    }

    public MessageJpaEntity(UUID id, UUID conversationId, SenderType senderType, UUID senderId,
                            String body, Instant sentAt) {
        super(id);
        this.conversationId = conversationId;
        this.senderType = senderType;
        this.senderId = senderId;
        this.body = body;
        this.sentAt = sentAt;
    }

    public UUID getConversationId() { return conversationId; }
    public SenderType getSenderType() { return senderType; }
    public UUID getSenderId() { return senderId; }
    public String getBody() { return body; }
    public Instant getSentAt() { return sentAt; }
}

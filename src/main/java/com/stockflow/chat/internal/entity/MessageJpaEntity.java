package com.stockflow.chat.internal.entity;

import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.MessageKind;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.chat.internal.domain.Visibility;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of {@code chat.message}. Not the domain model ({@code ChatMessage}). What was said, by whom, where and to
 * whom is not updatable: {@code tg_message_immutable} refuses it anyway, and Hibernate should not even try.
 */
@Entity
@Table(name = "message", schema = "chat")
public class MessageJpaEntity extends BaseEntity {

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private MessageKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16, updatable = false)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 32, updatable = false)
    private SenderType senderType;

    @Column(name = "sender_id", updatable = false)
    private UUID senderId;

    @Column(name = "body", length = 4000)
    private String body;

    @Column(name = "client_message_id", length = 64, updatable = false)
    private String clientMessageId;

    @Column(name = "context_id", updatable = false)
    private UUID contextId;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_kind", length = 32, updatable = false)
    private CardKind cardKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "card_payload", columnDefinition = "jsonb")
    private JsonNode cardPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_state", length = 16)
    private CardState cardState;

    @Column(name = "card_updated_at")
    private Instant cardUpdatedAt;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    protected MessageJpaEntity() {
    }

    public MessageJpaEntity(UUID id, UUID conversationId, long seq, MessageKind kind, Visibility visibility,
                            SenderType senderType, UUID senderId, String clientMessageId, UUID contextId,
                            CardKind cardKind, Instant sentAt) {
        super(id);
        this.conversationId = conversationId;
        this.seq = seq;
        this.kind = kind;
        this.visibility = visibility;
        this.senderType = senderType;
        this.senderId = senderId;
        this.clientMessageId = clientMessageId;
        this.contextId = contextId;
        this.cardKind = cardKind;
        this.sentAt = sentAt;
    }

    /** Copies what may change in: the text (with its edit time), the card, the deletion. */
    public void apply(String body, Instant editedAt, JsonNode cardPayload, CardState cardState, Instant cardUpdatedAt,
                      Instant deletedAt, UUID deletedBy) {
        this.body = body;
        this.editedAt = editedAt;
        this.cardPayload = cardPayload;
        this.cardState = cardState;
        this.cardUpdatedAt = cardUpdatedAt;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
    }

    public UUID getConversationId() { return conversationId; }
    public long getSeq() { return seq; }
    public MessageKind getKind() { return kind; }
    public Visibility getVisibility() { return visibility; }
    public SenderType getSenderType() { return senderType; }
    public UUID getSenderId() { return senderId; }
    public String getBody() { return body; }
    public String getClientMessageId() { return clientMessageId; }
    public UUID getContextId() { return contextId; }
    public CardKind getCardKind() { return cardKind; }
    public JsonNode getCardPayload() { return cardPayload; }
    public CardState getCardState() { return cardState; }
    public Instant getCardUpdatedAt() { return cardUpdatedAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getEditedAt() { return editedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public UUID getDeletedBy() { return deletedBy; }
}

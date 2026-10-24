package com.stockflow.assistant.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.security.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code assistant.conversation}. Owned by one user and read with scope OWN, so another user's conversation is not
 * found rather than forbidden.
 */
@Entity
@Table(name = "conversation", schema = "assistant")
public class AssistantConversationJpaEntity extends BaseEntity implements ScopedEntity {

    /** Read only for its attribute names by {@link ScopedEntity}; never persisted, never returned. */
    public static final AssistantConversationJpaEntity SCOPE_PROTOTYPE = new AssistantConversationJpaEntity();

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "title", length = 120)
    private String title;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "answering_since")
    private Instant answeringSince;

    protected AssistantConversationJpaEntity() {
    }

    public AssistantConversationJpaEntity(UUID id, UUID ownerUserId, Instant createdAt) {
        super(id);
        this.ownerUserId = ownerUserId;
        this.lastActivityAt = createdAt;
    }

    @Override
    public String ownerAttribute() {
        return "ownerUserId";
    }

    /** A conversation is a person's, never a warehouse's. */
    @Override
    public String warehouseAttribute() {
        return null;
    }

    /** Claims it for one question; sets the title from the first one. */
    public void claim(Instant at, String titleIfFirst) {
        this.answeringSince = at;
        if (title == null) {
            this.title = titleIfFirst;
        }
    }

    /** Counts a stored message and moves the activity time. */
    public int nextSequence(Instant at) {
        this.lastActivityAt = at;
        return messageCount++;
    }

    public void release() {
        this.answeringSince = null;
    }

    public UUID getOwnerUserId() { return ownerUserId; }
    public String getTitle() { return title; }
    public int getMessageCount() { return messageCount; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getAnsweringSince() { return answeringSince; }
}

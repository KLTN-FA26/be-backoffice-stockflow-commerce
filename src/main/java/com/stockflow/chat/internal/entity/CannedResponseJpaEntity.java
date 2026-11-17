package com.stockflow.chat.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of {@code chat.canned_response} (V20261124000300): a saved reply, an agent's own ({@code owner_id}) or
 * shared (none). Its owner never changes; deleting it sets {@code archived_at}.
 */
@Entity
@Table(name = "canned_response", schema = "chat")
public class CannedResponseJpaEntity extends BaseEntity {

    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "shortcut", length = 32)
    private String shortcut;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected CannedResponseJpaEntity() {
    }

    public CannedResponseJpaEntity(UUID id, UUID ownerId) {
        super(id);
        this.ownerId = ownerId;
    }

    public void apply(String title, String body, String shortcut, String category, Instant archivedAt) {
        this.title = title;
        this.body = body;
        this.shortcut = shortcut;
        this.category = category;
        this.archivedAt = archivedAt;
    }

    public UUID getOwnerId() { return ownerId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getShortcut() { return shortcut; }
    public String getCategory() { return category; }
    public Instant getArchivedAt() { return archivedAt; }
}

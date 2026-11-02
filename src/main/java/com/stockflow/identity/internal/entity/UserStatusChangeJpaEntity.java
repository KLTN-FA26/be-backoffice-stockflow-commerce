package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code identity.user_status_change}: one administrator action on an account's status, with the reason
 * (SCRUM-474 LOCK-REASON). Append-only - a trigger refuses UPDATE and DELETE, and every column is
 * {@code updatable = false} so Hibernate never tries. Statuses and the action are stored as text: the
 * table's CHECKs name the allowed values, and a row written under a status later renamed must still load.
 */
@Entity
@Table(name = "user_status_change", schema = "identity")
public class UserStatusChangeJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "action", nullable = false, length = 16, updatable = false)
    private String action;

    @Column(name = "from_status", nullable = false, length = 32, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32, updatable = false)
    private String toStatus;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_username", nullable = false, length = 320, updatable = false)
    private String actorUsername;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected UserStatusChangeJpaEntity() {
    }

    public UserStatusChangeJpaEntity(UUID id, UUID userId, String action, String fromStatus, String toStatus,
                                     String reason, UUID actorId, String actorUsername, Instant changedAt) {
        super(id);
        this.userId = userId;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorId = actorId;
        this.actorUsername = actorUsername;
        this.changedAt = changedAt;
    }

    public UUID getUserId() { return userId; }
    public String getAction() { return action; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public String getActorUsername() { return actorUsername; }
    public Instant getChangedAt() { return changedAt; }
}

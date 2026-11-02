package com.stockflow.order.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.return_request_status_history}: one status change of a return, who made it and why. Append-only;
 * statuses as text, as {@code OrderStatusHistoryJpaEntity} keeps them, so a row written under a status renamed later
 * still loads.
 */
@Entity
@Table(name = "return_request_status_history", schema = "ordering")
public class ReturnRequestStatusHistoryJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "return_request_id", nullable = false, updatable = false)
    private UUID returnRequestId;

    @Column(name = "from_status", length = 24, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 24, updatable = false)
    private String toStatus;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected ReturnRequestStatusHistoryJpaEntity() {
    }

    public ReturnRequestStatusHistoryJpaEntity(UUID id, UUID returnRequestId, String fromStatus, String toStatus,
                                               String reason, UUID actorId, Instant occurredAt) {
        this.id = id;
        this.returnRequestId = returnRequestId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getReturnRequestId() { return returnRequestId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
}

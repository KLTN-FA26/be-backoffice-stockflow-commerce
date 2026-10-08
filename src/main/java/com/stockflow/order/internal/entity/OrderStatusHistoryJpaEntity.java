package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.order_status_history}. Statuses are stored as text, not as the enum: the table's
 * CHECK accepts any upper-case name so the lifecycle can grow without a migration here, and a row
 * written under a status later renamed must still load.
 */
@Entity
@Table(name = "order_status_history", schema = "ordering")
public class OrderStatusHistoryJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "from_status", length = 32, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32, updatable = false)
    private String toStatus;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    /** Who made the change. Left null for now: the audit log records the actor of every guarded call. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected OrderStatusHistoryJpaEntity() {
    }

    public OrderStatusHistoryJpaEntity(UUID id, UUID orderId, String fromStatus, String toStatus,
                                       String reason, Instant occurredAt) {
        super(id);
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public UUID getOrderId() { return orderId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
}

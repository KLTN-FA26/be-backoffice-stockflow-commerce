package com.stockflow.production.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code production.production_order_status_history}: one immutable row per transition, for the order
 * detail screen and the shop dashboard. Not a {@code BaseEntity}: the table has neither a version nor
 * audit columns, because a history row is written once and never changed — {@code changed_by} and
 * {@code changed_at} are its audit. Always written with {@code persist}, never merged.
 */
@Entity
@Table(name = "production_order_status_history", schema = "production")
public class ProductionOrderStatusHistoryJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "production_order_id", nullable = false, updatable = false)
    private UUID productionOrderId;

    @Column(name = "from_status", length = 32, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32, updatable = false)
    private String toStatus;

    @Column(name = "reason", length = 1000, updatable = false)
    private String reason;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    protected ProductionOrderStatusHistoryJpaEntity() {
    }

    public ProductionOrderStatusHistoryJpaEntity(UUID id, UUID productionOrderId, String fromStatus, String toStatus,
                                                 String reason, Instant changedAt, UUID changedBy) {
        this.id = id;
        this.productionOrderId = productionOrderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
    }

    public UUID getId() { return id; }
    public UUID getProductionOrderId() { return productionOrderId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public Instant getChangedAt() { return changedAt; }
    public UUID getChangedBy() { return changedBy; }
}

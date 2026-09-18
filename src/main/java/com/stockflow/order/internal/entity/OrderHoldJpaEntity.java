package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Exception evidence is closed, never deleted, when integrity is restored. */
@Entity
@Table(name = "order_hold", schema = "ordering")
public class OrderHoldJpaEntity extends BaseEntity {
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "reason", nullable = false, updatable = false, length = 64) private String reason;
    @Column(name = "raised_at", nullable = false, updatable = false) private Instant raisedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolved_by") private UUID resolvedBy;
    @Column(name = "resolution_note", length = 1000) private String resolutionNote;
    protected OrderHoldJpaEntity() { }
    public OrderHoldJpaEntity(UUID id, UUID orderId, String reason, Instant raisedAt) {
        super(id); this.orderId = orderId; this.reason = reason; this.raisedAt = raisedAt;
    }
    public void resolve(UUID actor, String note, Instant now) {
        if (resolvedAt != null) { return; }
        if (note == null || note.isBlank()) { throw new IllegalArgumentException("A hold resolution needs evidence"); }
        resolvedBy = actor; resolutionNote = note; resolvedAt = now;
    }
}

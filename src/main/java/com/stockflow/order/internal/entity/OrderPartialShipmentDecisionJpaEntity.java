package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.order_partial_shipment_decision} (V20261022000400, PACK-PARTIAL): every time Sales allowed or stopped
 * shipping an order in parts - who, when, at what status, and why. Append-only, the order's history of the decision.
 */
@Entity
@Table(name = "order_partial_shipment_decision", schema = "ordering")
public class OrderPartialShipmentDecisionJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "allowed", nullable = false, updatable = false) private boolean allowed;
    @Column(name = "order_status", nullable = false, updatable = false, length = 32) private String orderStatus;
    @Column(name = "note", updatable = false, length = 500) private String note;
    @Column(name = "decided_by", updatable = false) private UUID decidedBy;
    @Column(name = "decided_at", nullable = false, updatable = false) private Instant decidedAt;

    protected OrderPartialShipmentDecisionJpaEntity() {
    }

    public OrderPartialShipmentDecisionJpaEntity(UUID id, UUID orderId, boolean allowed, String orderStatus, String note,
                                                 UUID decidedBy, Instant decidedAt) {
        super(id);
        this.orderId = orderId;
        this.allowed = allowed;
        this.orderStatus = orderStatus;
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    public UUID getOrderId() { return orderId; }
    public boolean isAllowed() { return allowed; }
    public String getOrderStatus() { return orderStatus; }
    public String getNote() { return note; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}

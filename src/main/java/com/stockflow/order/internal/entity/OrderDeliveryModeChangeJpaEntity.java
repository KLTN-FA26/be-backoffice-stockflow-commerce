package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.order_delivery_mode_change} (V20261022000800, LP-1): every time the Order Coordinator changed how an
 * order travels - from what to what, who, when, at what status, and why. Append-only, the order's history of the choice.
 */
@Entity
@Table(name = "order_delivery_mode_change", schema = "ordering")
public class OrderDeliveryModeChangeJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "delivery_mode", nullable = false, updatable = false, length = 16) private String deliveryMode;
    @Column(name = "previous_mode", nullable = false, updatable = false, length = 16) private String previousMode;
    @Column(name = "order_status", nullable = false, updatable = false, length = 32) private String orderStatus;
    @Column(name = "note", updatable = false, length = 500) private String note;
    @Column(name = "changed_by", updatable = false) private UUID changedBy;
    @Column(name = "changed_at", nullable = false, updatable = false) private Instant changedAt;

    protected OrderDeliveryModeChangeJpaEntity() {
    }

    public OrderDeliveryModeChangeJpaEntity(UUID id, UUID orderId, String deliveryMode, String previousMode,
                                            String orderStatus, String note, UUID changedBy, Instant changedAt) {
        super(id);
        this.orderId = orderId;
        this.deliveryMode = deliveryMode;
        this.previousMode = previousMode;
        this.orderStatus = orderStatus;
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public UUID getOrderId() { return orderId; }
    public String getDeliveryMode() { return deliveryMode; }
    public String getPreviousMode() { return previousMode; }
    public String getOrderStatus() { return orderStatus; }
    public String getNote() { return note; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}

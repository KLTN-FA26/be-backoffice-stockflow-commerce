package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.order_remainder_closure} (V20261125000100, WF4-CLOSE-HIST-SCHEMA): every time Sales closed the rest
 * of a partly fulfilled order - who, when and why - even when the closure left the order's status where it was. Append-only,
 * the order's history of closures, modelled on {@link OrderPartialShipmentDecisionJpaEntity}.
 */
@Entity
@Table(name = "order_remainder_closure", schema = "ordering")
public class OrderRemainderClosureJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "closure_id", nullable = false, updatable = false) private UUID closureId;
    @Column(name = "reason_code", nullable = false, updatable = false, length = 32) private String reasonCode;
    @Column(name = "note", updatable = false, length = 1000) private String note;
    @Column(name = "closed_by", updatable = false) private UUID closedBy;
    @Column(name = "closed_at", nullable = false, updatable = false) private Instant closedAt;

    protected OrderRemainderClosureJpaEntity() {
    }

    public OrderRemainderClosureJpaEntity(UUID id, UUID orderId, UUID closureId, String reasonCode, String note,
                                          UUID closedBy, Instant closedAt) {
        super(id);
        this.orderId = orderId;
        this.closureId = closureId;
        this.reasonCode = reasonCode;
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.closedBy = closedBy;
        this.closedAt = closedAt;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getClosureId() { return closureId; }
    public String getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public UUID getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
}

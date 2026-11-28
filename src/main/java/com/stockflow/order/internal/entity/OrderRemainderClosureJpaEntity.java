package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    /** WF-05 Q-19 edge: the shipping fee payment waived with this closure; 0 when none (V20261217000400). */
    @Column(name = "shipping_fee_waived", nullable = false, updatable = false, precision = 19, scale = 4)
    private java.math.BigDecimal shippingFeeWaived = java.math.BigDecimal.ZERO;
    /** D50 (V20270129000550): the fee this closure kept, as entered, and who bears it; both null when it kept none. */
    @Column(name = "fee_amount", updatable = false, precision = 19, scale = 4)
    private java.math.BigDecimal feeAmount;
    @Column(name = "fee_bearer", updatable = false, length = 8)
    @Enumerated(EnumType.STRING)
    private com.stockflow.order.api.CancellationFeeBearer feeBearer;

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

    /** As above, with the shipping fee payment waived with the closure (WF-05 Q-19 edge). */
    public OrderRemainderClosureJpaEntity(UUID id, UUID orderId, UUID closureId, String reasonCode, String note,
                                          UUID closedBy, Instant closedAt, java.math.BigDecimal shippingFeeWaived) {
        this(id, orderId, closureId, reasonCode, note, closedBy, closedAt);
        this.shippingFeeWaived = shippingFeeWaived == null ? java.math.BigDecimal.ZERO : shippingFeeWaived;
    }

    /** As above, with the fee the closure kept and who bears it (D50); both null for a closure that kept none. */
    public OrderRemainderClosureJpaEntity(UUID id, UUID orderId, UUID closureId, String reasonCode, String note,
                                          UUID closedBy, Instant closedAt, java.math.BigDecimal shippingFeeWaived,
                                          java.math.BigDecimal feeAmount,
                                          com.stockflow.order.api.CancellationFeeBearer feeBearer) {
        this(id, orderId, closureId, reasonCode, note, closedBy, closedAt, shippingFeeWaived);
        this.feeAmount = feeAmount;
        this.feeBearer = feeBearer;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getClosureId() { return closureId; }
    public String getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public UUID getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
    public java.math.BigDecimal getShippingFeeWaived() { return shippingFeeWaived; }
    public java.math.BigDecimal getFeeAmount() { return feeAmount; }
    public com.stockflow.order.api.CancellationFeeBearer getFeeBearer() { return feeBearer; }
}

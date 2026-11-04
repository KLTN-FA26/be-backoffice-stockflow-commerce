package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code ordering.order_cancellation_request}. The request is immutable; only the decision moves. */
@Entity
@Table(name = "order_cancellation_request", schema = "ordering")
public class OrderCancellationRequestJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32, updatable = false)
    private CancellationReasonCode reasonCode;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CancellationRequestStatus status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "retained_percent", precision = 5, scale = 2)
    private BigDecimal retainedPercent;

    protected OrderCancellationRequestJpaEntity() {
    }

    public OrderCancellationRequestJpaEntity(UUID id, UUID orderId, UUID requestedBy, CancellationReasonCode reasonCode,
                                             String note, Instant requestedAt) {
        super(id);
        this.orderId = orderId;
        this.requestedBy = requestedBy;
        this.reasonCode = reasonCode;
        this.note = note;
        this.requestedAt = requestedAt;
        this.status = CancellationRequestStatus.PENDING;
    }

    public void decide(CancellationRequestStatus status, UUID decidedBy, Instant decidedAt, String decisionNote,
                       BigDecimal retainedPercent) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.retainedPercent = retainedPercent;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getRequestedBy() { return requestedBy; }
    public CancellationReasonCode getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public Instant getRequestedAt() { return requestedAt; }
    public CancellationRequestStatus getStatus() { return status; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public BigDecimal getRetainedPercent() { return retainedPercent; }
}

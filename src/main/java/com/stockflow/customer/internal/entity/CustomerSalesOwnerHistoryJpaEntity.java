package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.SalesOwnerState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code customer.customer_sales_owner_history} (V20270128000100): one change of a customer's Sales owner. Append-only -
 * a trigger refuses UPDATE and DELETE, and every column is {@code updatable = false} so Hibernate never tries.
 */
@Entity
@Table(name = "customer_sales_owner_history", schema = "customer")
public class CustomerSalesOwnerHistoryJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "from_user_id", updatable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", updatable = false)
    private UUID toUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 32, updatable = false)
    private SalesOwnerState toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32, updatable = false)
    private SalesOwnerChangeReason reason;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "batch_id", updatable = false)
    private UUID batchId;

    protected CustomerSalesOwnerHistoryJpaEntity() {
    }

    public CustomerSalesOwnerHistoryJpaEntity(UUID id, UUID customerId, UUID fromUserId, UUID toUserId,
                                              SalesOwnerState toState, SalesOwnerChangeReason reason, String note,
                                              UUID changedBy, Instant changedAt, UUID batchId) {
        super(id);
        this.customerId = customerId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.toState = toState;
        this.reason = reason;
        this.note = note;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.batchId = batchId;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getFromUserId() { return fromUserId; }
    public UUID getToUserId() { return toUserId; }
    public SalesOwnerState getToState() { return toState; }
    public SalesOwnerChangeReason getReason() { return reason; }
    public String getNote() { return note; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public UUID getBatchId() { return batchId; }
}

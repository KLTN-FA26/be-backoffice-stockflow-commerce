package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.customer.internal.domain.CustomerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code customer.customer_status_history}. Written once, never updated. */
@Entity
@Table(name = "customer_status_history", schema = "customer")
public class CustomerStatusHistoryJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32, updatable = false)
    private CustomerStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32, updatable = false)
    private CustomerStatus toStatus;

    @Column(name = "reason", length = 1000, updatable = false)
    private String reason;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected CustomerStatusHistoryJpaEntity() {
    }

    public CustomerStatusHistoryJpaEntity(UUID id, UUID customerId, CustomerStatus fromStatus, CustomerStatus toStatus,
                                          String reason, UUID changedBy, Instant changedAt) {
        super(id);
        this.customerId = customerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public UUID getCustomerId() { return customerId; }
    public CustomerStatus getFromStatus() { return fromStatus; }
    public CustomerStatus getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}

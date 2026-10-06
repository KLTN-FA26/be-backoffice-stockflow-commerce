package com.stockflow.fulfillment.internal.entity;

import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;
import java.time.Instant;

/** JPA mapping of a pick (table {@code fulfillment.pick}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "pick", schema = "fulfillment")
public class PickJpaEntity extends BaseEntity {

    @Column(name = "wave_id")
    private UUID waveId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PickStatus status;
    @Column(name = "assigned_user_id") private UUID assignedUserId;
    @Column(name = "assigned_at") private Instant assignedAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "picked_at") private Instant pickedAt;

    protected PickJpaEntity() {
    }

    public PickJpaEntity(UUID id, UUID waveId, UUID orderId, PickStatus status) {
        super(id);
        this.waveId = waveId;
        this.orderId = orderId;
        this.status = status;
    }
    public void assign(UUID userId, Instant now) {
        if (status != PickStatus.PENDING) {
            throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.CONFLICT,
                    "A pick can only be assigned while pending");
        }
        assignedUserId = java.util.Objects.requireNonNull(userId);
        assignedAt = java.util.Objects.requireNonNull(now);
    }
    public void start(UUID actor, Instant now) {
        requireAssigned(actor);
        if (status != PickStatus.PENDING) { throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.CONFLICT); }
        status = PickStatus.PICKING; startedAt = now;
    }
    public void complete(UUID actor, Instant now) {
        requireAssigned(actor);
        if (status != PickStatus.PICKING) { throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.CONFLICT); }
        status = PickStatus.PICKED; pickedAt = now;
    }
    public void requireAssigned(UUID actor) {
        if (actor == null || !actor.equals(assignedUserId)) {
            throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.NOT_FOUND,
                    "Fulfillment task is not assigned to this user");
        }
    }

    public UUID getWaveId() { return waveId; }
    public UUID getOrderId() { return orderId; }
    public PickStatus getStatus() { return status; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getPickedAt() { return pickedAt; }
}

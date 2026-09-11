package com.stockflow.fulfillment.internal.entity;

import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

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

    protected PickJpaEntity() {
    }

    public PickJpaEntity(UUID id, UUID waveId, UUID orderId, PickStatus status) {
        super(id);
        this.waveId = waveId;
        this.orderId = orderId;
        this.status = status;
    }

    public UUID getWaveId() { return waveId; }
    public UUID getOrderId() { return orderId; }
    public PickStatus getStatus() { return status; }
}

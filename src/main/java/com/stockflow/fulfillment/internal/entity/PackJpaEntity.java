package com.stockflow.fulfillment.internal.entity;

import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a pack (table {@code fulfillment.pack}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "pack", schema = "fulfillment")
public class PackJpaEntity extends BaseEntity {

    @Column(name = "pick_id", nullable = false)
    private UUID pickId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PackStatus status;

    @Column(name = "packed_at")
    private Instant packedAt;

    protected PackJpaEntity() {
    }

    public PackJpaEntity(UUID id, UUID pickId, PackStatus status, Instant packedAt) {
        super(id);
        this.pickId = pickId;
        this.status = status;
        this.packedAt = packedAt;
    }

    public UUID getPickId() { return pickId; }
    public PackStatus getStatus() { return status; }
    public Instant getPackedAt() { return packedAt; }
}

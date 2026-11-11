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

/**
 * JPA mapping of a task's check session (table {@code fulfillment.pack}; team plan 4C-6, kltn-docs 08 §5.1). Not the
 * domain model: the rules are on {@code CartonCheck} (and on {@code PackingSession}, the repack station kept until 4C-7),
 * which {@link #apply} copies back. {@code packed_at} / {@code packed_by} are when and by whom it was COMPLETED.
 */
@Entity
@Table(name = "pack", schema = "fulfillment")
public class PackJpaEntity extends BaseEntity {

    /** The pick (fulfilment task) whose packing session this is. */
    @Column(name = "pick_id", nullable = false)
    private UUID pickId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PackStatus status;

    @Column(name = "packed_at")
    private Instant packedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "started_by")
    private UUID startedBy;
    @Column(name = "packed_by")
    private UUID packedBy;

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
    public Instant getStartedAt() { return startedAt; }
    public UUID getStartedBy() { return startedBy; }
    public UUID getPackedBy() { return packedBy; }

    /** The order was cancelled (SCRUM-460, 08 BR-04): any label made for its cartons is void with it. */
    public void cancel() { status = PackStatus.CANCELLED; }

    /** What the packing session decided. */
    public void apply(PackStatus status, Instant startedAt, UUID startedBy, Instant packedAt, UUID packedBy) {
        this.status = status;
        this.startedAt = startedAt;
        this.startedBy = startedBy;
        this.packedAt = packedAt;
        this.packedBy = packedBy;
    }
}

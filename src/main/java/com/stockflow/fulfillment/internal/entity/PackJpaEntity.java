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
 * JPA mapping of a pack - the packing session of a task (table {@code fulfillment.pack}). Not the domain model: the
 * rules are on {@code PackingSession}, which {@link #apply} copies back.
 */
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
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "started_by")
    private UUID startedBy;
    @Column(name = "packed_by")
    private UUID packedBy;
    /** The request id of a completion a design check put on hold: the same request again gets the same answer. */
    @Column(name = "held_request_id")
    private UUID heldRequestId;

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
    public UUID getHeldRequestId() { return heldRequestId; }
    public void holdFor(UUID requestId) { this.heldRequestId = requestId; }

    /** The order was cancelled (SCRUM-460): any label made for the parcel is void with it. */
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

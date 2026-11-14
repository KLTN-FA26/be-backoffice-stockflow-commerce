package com.stockflow.warehouse.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.warehouse.internal.domain.PutawayTripItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a carton on a putaway trip's cart (table {@code warehouse.putaway_trip_item}). Not the domain model:
 * the row is changed only through its trip.
 */
@Entity
@Table(name = "putaway_trip_item", schema = "warehouse")
public class PutawayTripItemJpaEntity extends BaseEntity {

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "putaway_task_id", nullable = false, updatable = false)
    private UUID putawayTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PutawayTripItemStatus status;

    /** A {@code warehouse.storage_location}: the bin assigned when the trip set off, or an OVERFLOW area. */
    @Column(name = "assigned_location_id")
    private UUID assignedLocationId;

    @Column(name = "stop_sequence")
    private Integer stopSequence;

    /** Null while the space at the assigned bin is held ({@code ck_putaway_trip_item_hold}). */
    @Column(name = "hold_released_at")
    private Instant holdReleasedAt;

    @Column(name = "loaded_at", nullable = false)
    private Instant loadedAt;

    @Column(name = "placed_at")
    private Instant placedAt;

    protected PutawayTripItemJpaEntity() {
    }

    public PutawayTripItemJpaEntity(UUID id, UUID tripId, UUID putawayTaskId) {
        super(id);
        this.tripId = tripId;
        this.putawayTaskId = putawayTaskId;
    }

    public void apply(PutawayTripItemStatus status, UUID assignedLocationId, Integer stopSequence,
                      Instant holdReleasedAt, Instant loadedAt, Instant placedAt) {
        this.status = status;
        this.assignedLocationId = assignedLocationId;
        this.stopSequence = stopSequence;
        this.holdReleasedAt = holdReleasedAt;
        this.loadedAt = loadedAt;
        this.placedAt = placedAt;
    }

    public UUID getTripId() { return tripId; }
    public UUID getPutawayTaskId() { return putawayTaskId; }
    public PutawayTripItemStatus getStatus() { return status; }
    public UUID getAssignedLocationId() { return assignedLocationId; }
    public Integer getStopSequence() { return stopSequence; }
    public Instant getHoldReleasedAt() { return holdReleasedAt; }
    public Instant getLoadedAt() { return loadedAt; }
    public Instant getPlacedAt() { return placedAt; }
}

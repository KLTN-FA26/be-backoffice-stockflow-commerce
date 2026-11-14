package com.stockflow.warehouse.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.warehouse.internal.domain.PutawayTripStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a putaway trip (table {@code warehouse.putaway_trip}). Not the domain model.
 *
 * <p>Its cartons are {@link PutawayTripItemJpaEntity} rows read by trip id, not a mapped collection: a trip is always
 * loaded whole by the adapter, and a lazy collection would only add a way to read half of one.</p>
 */
@Entity
@Table(name = "putaway_trip", schema = "warehouse")
public class PutawayTripJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "operator_id", nullable = false, updatable = false)
    private UUID operatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PutawayTripStatus status;

    /** A {@code warehouse.storage_location}: the source area the route starts from. */
    @Column(name = "start_location_id")
    private UUID startLocationId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    protected PutawayTripJpaEntity() {
    }

    public PutawayTripJpaEntity(UUID id, UUID warehouseId, UUID operatorId, PutawayTripStatus status,
                                Instant lastActivityAt) {
        super(id);
        this.warehouseId = warehouseId;
        this.operatorId = operatorId;
        this.status = status;
        this.lastActivityAt = lastActivityAt;
    }

    /** Everything a trip changes after it is opened. */
    public void apply(PutawayTripStatus status, UUID startLocationId, Instant startedAt, Instant lastActivityAt,
                      Instant closedAt, UUID closedBy) {
        this.status = status;
        this.startLocationId = startLocationId;
        this.startedAt = startedAt;
        this.lastActivityAt = lastActivityAt;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public UUID getOperatorId() { return operatorId; }
    public PutawayTripStatus getStatus() { return status; }
    public UUID getStartLocationId() { return startLocationId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getClosedBy() { return closedBy; }
}

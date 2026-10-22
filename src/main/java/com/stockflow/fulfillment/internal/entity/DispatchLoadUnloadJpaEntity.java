package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A parcel to take off a vehicle being loaded because its order was cancelled (table
 * {@code fulfillment.dispatch_load_unload}, SCRUM-392): pending until the dispatcher scans it off.
 */
@Entity
@Table(name = "dispatch_load_unload", schema = "fulfillment")
public class DispatchLoadUnloadJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "reason", nullable = false, updatable = false, length = 500) private String reason;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "unloaded_at") private Instant unloadedAt;
    @Column(name = "unloaded_by") private UUID unloadedBy;

    protected DispatchLoadUnloadJpaEntity() {
    }

    public DispatchLoadUnloadJpaEntity(UUID id, UUID loadId, UUID packageId, UUID shipmentId, String reason,
                                       Instant requestedAt) {
        super(id);
        this.loadId = loadId;
        this.packageId = packageId;
        this.shipmentId = shipmentId;
        this.reason = reason;
        this.requestedAt = requestedAt;
    }

    public void apply(Instant unloadedAt, UUID unloadedBy) {
        this.unloadedAt = unloadedAt;
        this.unloadedBy = unloadedBy;
    }

    public UUID getLoadId() { return loadId; }
    public UUID getPackageId() { return packageId; }
    public UUID getShipmentId() { return shipmentId; }
    public String getReason() { return reason; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getUnloadedAt() { return unloadedAt; }
    public UUID getUnloadedBy() { return unloadedBy; }
}

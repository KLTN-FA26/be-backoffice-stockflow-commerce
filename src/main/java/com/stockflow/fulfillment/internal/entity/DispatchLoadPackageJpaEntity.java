package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A parcel scanned onto a load's vehicle (table {@code fulfillment.dispatch_load_package}). */
@Entity
@Table(name = "dispatch_load_package", schema = "fulfillment")
public class DispatchLoadPackageJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Column(name = "shipment_id", updatable = false) private UUID shipmentId;
    /** SCRUM-287 PR T: the transfer order of a parcel on a TRANSFER plan's vehicle (shipment_id null then). */
    @Column(name = "transfer_order_id", updatable = false) private UUID transferOrderId;
    @Column(name = "scan_id", nullable = false) private UUID scanId;
    @Column(name = "loaded_at", nullable = false) private Instant loadedAt;
    @Column(name = "loaded_by", nullable = false) private UUID loadedBy;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "removed_at") private Instant removedAt;
    @Column(name = "removed_by") private UUID removedBy;

    protected DispatchLoadPackageJpaEntity() {
    }

    public DispatchLoadPackageJpaEntity(UUID id, UUID loadId, UUID packageId, UUID shipmentId) {
        this(id, loadId, packageId, shipmentId, false);
    }

    /** @param transfer {@code key} is the parcel's transfer order (SCRUM-287 PR T), not a shipment */
    public DispatchLoadPackageJpaEntity(UUID id, UUID loadId, UUID packageId, UUID key, boolean transfer) {
        super(id);
        this.loadId = loadId;
        this.packageId = packageId;
        this.shipmentId = transfer ? null : key;
        this.transferOrderId = transfer ? key : null;
    }

    public void apply(UUID scanId, Instant loadedAt, UUID loadedBy, boolean active, Instant removedAt, UUID removedBy) {
        this.scanId = scanId;
        this.loadedAt = loadedAt;
        this.loadedBy = loadedBy;
        this.active = active;
        this.removedAt = removedAt;
        this.removedBy = removedBy;
    }

    public UUID getLoadId() { return loadId; }
    public UUID getPackageId() { return packageId; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getTransferOrderId() { return transferOrderId; }

    /** What it is loaded with: its shipment, or its transfer order. */
    public UUID getKey() { return shipmentId != null ? shipmentId : transferOrderId; }
    public UUID getScanId() { return scanId; }
    public Instant getLoadedAt() { return loadedAt; }
    public UUID getLoadedBy() { return loadedBy; }
    public boolean isActive() { return active; }
    public Instant getRemovedAt() { return removedAt; }
    public UUID getRemovedBy() { return removedBy; }
}

package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadPlanReleaseReason;
import com.stockflow.fulfillment.internal.domain.LoadPlanUnplacedReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A parcel a load plan holds or held (table {@code fulfillment.load_plan_package}; BR-09: at most one active row per
 * parcel, {@code uk_load_plan_package_active}).
 */
@Entity
@Table(name = "load_plan_package", schema = "fulfillment")
public class LoadPlanPackageJpaEntity extends BaseEntity {

    @Column(name = "load_plan_id", nullable = false, updatable = false) private UUID loadPlanId;
    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Column(name = "active", nullable = false) private boolean active;
    @Enumerated(EnumType.STRING)
    @Column(name = "unplaced_reason", length = 24) private LoadPlanUnplacedReason unplacedReason;
    @Column(name = "added_at", nullable = false) private Instant addedAt;
    @Column(name = "added_by") private UUID addedBy;
    @Column(name = "released_at") private Instant releasedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 24) private LoadPlanReleaseReason releaseReason;

    protected LoadPlanPackageJpaEntity() {
    }

    public LoadPlanPackageJpaEntity(UUID id, UUID loadPlanId, UUID packageId) {
        super(id);
        this.loadPlanId = loadPlanId;
        this.packageId = packageId;
    }

    public void apply(LoadPlan.Member m) {
        this.shipmentId = m.shipmentId();
        this.active = m.active();
        this.unplacedReason = m.unplacedReason();
        this.addedAt = m.addedAt();
        this.addedBy = m.addedBy();
        this.releasedAt = m.releasedAt();
        this.releaseReason = m.releaseReason();
    }

    public LoadPlan.Member toMember() {
        return new LoadPlan.Member(packageId, shipmentId, active, unplacedReason, addedAt, addedBy, releasedAt,
                releaseReason);
    }

    public UUID getLoadPlanId() { return loadPlanId; }
    public UUID getPackageId() { return packageId; }
    public UUID getShipmentId() { return shipmentId; }
    public boolean isActive() { return active; }
    public LoadPlanUnplacedReason getUnplacedReason() { return unplacedReason; }
    public LoadPlanReleaseReason getReleaseReason() { return releaseReason; }
}

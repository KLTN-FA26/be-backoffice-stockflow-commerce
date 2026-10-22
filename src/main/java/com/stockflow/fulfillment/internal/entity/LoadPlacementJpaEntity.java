package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.loadplanning.Orientation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Where a parcel sits in a vehicle of a load plan (table {@code fulfillment.load_placement}; 09 §3
 * {@code LoadPlacement}). One row per parcel and plan: a move between vehicles updates it.
 */
@Entity
@Table(name = "load_placement", schema = "fulfillment")
public class LoadPlacementJpaEntity extends BaseEntity {

    @Column(name = "load_plan_id", nullable = false, updatable = false) private UUID loadPlanId;
    @Column(name = "load_plan_vehicle_id", nullable = false) private UUID loadPlanVehicleId;
    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Column(name = "x_mm", nullable = false) private int xMm;
    @Column(name = "y_mm", nullable = false) private int yMm;
    @Column(name = "z_mm", nullable = false) private int zMm;
    @Column(name = "w_mm", nullable = false) private int wMm;
    @Column(name = "h_mm", nullable = false) private int hMm;
    @Column(name = "d_mm", nullable = false) private int dMm;
    @Column(name = "orientation", nullable = false) private short orientation;

    protected LoadPlacementJpaEntity() {
    }

    public LoadPlacementJpaEntity(UUID id, UUID loadPlanId, UUID packageId) {
        super(id);
        this.loadPlanId = loadPlanId;
        this.packageId = packageId;
    }

    /** Whether this row already says exactly that. */
    public boolean sameAs(UUID vehicleId, LoadPlan.Placed p) {
        return vehicleId.equals(loadPlanVehicleId) && xMm == p.x() && yMm == p.y() && zMm == p.z() && wMm == p.w()
                && hMm == p.h() && dMm == p.d() && orientation == p.orientation().index();
    }

    public void apply(UUID vehicleId, LoadPlan.Placed p) {
        this.loadPlanVehicleId = vehicleId;
        this.xMm = p.x();
        this.yMm = p.y();
        this.zMm = p.z();
        this.wMm = p.w();
        this.hMm = p.h();
        this.dMm = p.d();
        this.orientation = (short) p.orientation().index();
    }

    public LoadPlan.Placed toPlaced() {
        return new LoadPlan.Placed(packageId, xMm, yMm, zMm, wMm, hMm, dMm, Orientation.ofIndex(orientation));
    }

    public UUID getLoadPlanId() { return loadPlanId; }
    public UUID getLoadPlanVehicleId() { return loadPlanVehicleId; }
    public UUID getPackageId() { return packageId; }
}

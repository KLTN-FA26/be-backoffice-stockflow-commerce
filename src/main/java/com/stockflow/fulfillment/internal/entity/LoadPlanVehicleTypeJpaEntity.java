package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** A vehicle type a load plan may use (table {@code fulfillment.load_plan_vehicle_type}; 09 §4.2 step 1). */
@Entity
@Table(name = "load_plan_vehicle_type", schema = "fulfillment")
public class LoadPlanVehicleTypeJpaEntity extends BaseEntity {

    @Column(name = "load_plan_id", nullable = false, updatable = false) private UUID loadPlanId;
    @Column(name = "vehicle_type_id", nullable = false, updatable = false) private UUID vehicleTypeId;
    @Column(name = "sort_order", nullable = false) private int sortOrder;

    protected LoadPlanVehicleTypeJpaEntity() {
    }

    public LoadPlanVehicleTypeJpaEntity(UUID id, UUID loadPlanId, UUID vehicleTypeId, int sortOrder) {
        super(id);
        this.loadPlanId = loadPlanId;
        this.vehicleTypeId = vehicleTypeId;
        this.sortOrder = sortOrder;
    }

    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public UUID getLoadPlanId() { return loadPlanId; }
    public UUID getVehicleTypeId() { return vehicleTypeId; }
    public int getSortOrder() { return sortOrder; }
}

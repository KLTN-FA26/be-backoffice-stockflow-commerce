package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One vehicle of a load plan (table {@code fulfillment.load_plan_vehicle}; 09 §3 {@code LoadPlanVehicle}), with its
 * type's figures as they were when the plan was calculated.
 */
@Entity
@Table(name = "load_plan_vehicle", schema = "fulfillment")
public class LoadPlanVehicleJpaEntity extends BaseEntity {

    @Column(name = "load_plan_id", nullable = false, updatable = false) private UUID loadPlanId;
    @Column(name = "seq", nullable = false, updatable = false) private int seq;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "vehicle_type_id", nullable = false, updatable = false) private UUID vehicleTypeId;
    @Column(name = "vehicle_code", nullable = false, updatable = false, length = 32) private String vehicleCode;
    @Column(name = "vehicle_kind", nullable = false, updatable = false, length = 16) private String vehicleKind;
    @Column(name = "inner_length_mm", nullable = false, updatable = false) private int innerLengthMm;
    @Column(name = "inner_width_mm", nullable = false, updatable = false) private int innerWidthMm;
    @Column(name = "inner_height_mm", nullable = false, updatable = false) private int innerHeightMm;
    @Column(name = "max_payload_kg", nullable = false, updatable = false, precision = 10, scale = 3)
    private BigDecimal maxPayloadKg;
    @Column(name = "gusset_mm", nullable = false, updatable = false) private int gussetMm;
    @Column(name = "door_width_mm", updatable = false) private Integer doorWidthMm;
    @Column(name = "door_height_mm", updatable = false) private Integer doorHeightMm;
    @Column(name = "used_volume_mm3", nullable = false) private long usedVolumeMm3;
    @Column(name = "used_weight_kg", nullable = false, precision = 12, scale = 3) private BigDecimal usedWeightKg;
    @Column(name = "volume_fill_pct", nullable = false, precision = 7, scale = 2) private BigDecimal volumeFillPct;
    @Column(name = "weight_fill_pct", nullable = false, precision = 7, scale = 2) private BigDecimal weightFillPct;
    /** Parcels were taken out from under it (an order cancelled): recalculate before the plan is confirmed. */
    @Column(name = "needs_recalculation", nullable = false) private boolean needsRecalculation;

    protected LoadPlanVehicleJpaEntity() {
    }

    public LoadPlanVehicleJpaEntity(UUID id, UUID loadPlanId, int seq, VehicleSpec spec) {
        super(id);
        this.loadPlanId = loadPlanId;
        this.seq = seq;
        this.vehicleTypeId = spec.typeId();
        this.vehicleCode = spec.code();
        this.vehicleKind = spec.kind().name();
        this.innerLengthMm = spec.innerLengthMm();
        this.innerWidthMm = spec.innerWidthMm();
        this.innerHeightMm = spec.innerHeightMm();
        this.maxPayloadKg = spec.maxPayloadKg();
        this.gussetMm = spec.gussetMm();
        this.doorWidthMm = spec.doorWidthMm();
        this.doorHeightMm = spec.doorHeightMm();
    }

    public void apply(boolean active, long usedVolumeMm3, BigDecimal usedWeightKg, BigDecimal volumeFillPct,
                      BigDecimal weightFillPct, boolean needsRecalculation) {
        this.needsRecalculation = needsRecalculation;
        this.active = active;
        this.usedVolumeMm3 = usedVolumeMm3;
        this.usedWeightKg = usedWeightKg;
        this.volumeFillPct = volumeFillPct;
        this.weightFillPct = weightFillPct;
    }

    /** The type as copied: one vehicle of it ({@code availableCount} 1), for checking and re-solving this vehicle. */
    public VehicleSpec toSpec() {
        return new VehicleSpec(vehicleTypeId, vehicleCode, VehicleKind.valueOf(vehicleKind), innerLengthMm,
                innerWidthMm, innerHeightMm, maxPayloadKg, 1, gussetMm, doorWidthMm, doorHeightMm);
    }

    public UUID getLoadPlanId() { return loadPlanId; }
    public int getSeq() { return seq; }
    public boolean isActive() { return active; }
    public UUID getVehicleTypeId() { return vehicleTypeId; }
    public String getVehicleCode() { return vehicleCode; }
    public long getUsedVolumeMm3() { return usedVolumeMm3; }
    public BigDecimal getUsedWeightKg() { return usedWeightKg; }
    public BigDecimal getVolumeFillPct() { return volumeFillPct; }
    public BigDecimal getWeightFillPct() { return weightFillPct; }
    public boolean isNeedsRecalculation() { return needsRecalculation; }
}

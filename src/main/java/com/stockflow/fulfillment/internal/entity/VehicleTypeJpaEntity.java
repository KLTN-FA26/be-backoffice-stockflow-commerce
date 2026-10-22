package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A kind of truck, pickup or container a load plan may use (table {@code fulfillment.vehicle_type}; kltn-docs 09 §3 "Danh mục
 * loại xe"). The inner dimensions (mm) are the box the placement solver fills; {@code gussetMm} is the edge of the reinforcement cubes kept free in the top corners (BR-10; two at the front-end top corners, four corner-post cubes on a 45' box, 0 = none; 2 x gusset < width, gusset < height); {@code availableCount} caps how many
 * vehicles of the type a plan uses (BR-12); {@code rentalPrice} is reference only. The four ISO containers are
 * {@code isPreset}: never deleted, and the code of any type never changes.
 */
@Entity
@Table(name = "vehicle_type", schema = "fulfillment")
public class VehicleTypeJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32, updatable = false) private String code;
    @Column(name = "name", nullable = false, length = 120) private String name;
    @Column(name = "kind", nullable = false, length = 16) private String kind;
    @Column(name = "inner_length_mm", nullable = false) private int innerLengthMm;
    @Column(name = "inner_width_mm", nullable = false) private int innerWidthMm;
    @Column(name = "inner_height_mm", nullable = false) private int innerHeightMm;
    @Column(name = "gusset_mm", nullable = false) private int gussetMm;
    @Column(name = "max_payload_kg", nullable = false, precision = 10, scale = 3) private BigDecimal maxPayloadKg;
    @Column(name = "available_count", nullable = false) private int availableCount;
    @Column(name = "rental_price", precision = 19, scale = 4) private BigDecimal rentalPrice;
    @Column(name = "is_preset", nullable = false, updatable = false) private boolean preset;
    @Column(name = "status", nullable = false, length = 16) private String status;

    protected VehicleTypeJpaEntity() {
    }

    /** A declared (not preset) type: presets come from the migration only. */
    public VehicleTypeJpaEntity(UUID id, String code) {
        super(id);
        this.code = code;
        this.status = "ACTIVE";
    }

    public void apply(String name, String kind, int innerLengthMm, int innerWidthMm, int innerHeightMm,
                      int gussetMm, BigDecimal maxPayloadKg, int availableCount, BigDecimal rentalPrice, String status) {
        this.name = name;
        this.kind = kind;
        this.innerLengthMm = innerLengthMm;
        this.innerWidthMm = innerWidthMm;
        this.innerHeightMm = innerHeightMm;
        this.gussetMm = gussetMm;
        this.maxPayloadKg = maxPayloadKg;
        this.availableCount = availableCount;
        this.rentalPrice = rentalPrice;
        this.status = status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public int getInnerLengthMm() { return innerLengthMm; }
    public int getInnerWidthMm() { return innerWidthMm; }
    public int getInnerHeightMm() { return innerHeightMm; }
    public int getGussetMm() { return gussetMm; }
    public BigDecimal getMaxPayloadKg() { return maxPayloadKg; }
    public int getAvailableCount() { return availableCount; }
    public BigDecimal getRentalPrice() { return rentalPrice; }
    public boolean isPreset() { return preset; }
    public String getStatus() { return status; }
}

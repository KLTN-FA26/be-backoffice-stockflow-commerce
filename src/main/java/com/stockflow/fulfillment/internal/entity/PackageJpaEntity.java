package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.CartonCheck;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of table {@code fulfillment.package}: a shipping carton of the check station (team plan 4C-6; rules on
 * {@code CartonCheck.Carton}, a row with {@code pickLineId}) or a parcel of the repack station kept until 4C-7 (rules on
 * {@code PackingSession.Parcel}). Not the domain model: the repository adapters copy between the two. Shipments, load
 * plans and manifests take either kind ({@code PackageStatus#ships}). {@code tracking_number} and {@code label_url} are
 * not mapped: the system calls no carrier (kltn-docs 09 §1), the label is the internal one ({@code label_code}).
 */
@Entity
@Table(name = "package", schema = "fulfillment")
public class PackageJpaEntity extends BaseEntity {

    @Column(name = "pack_id", nullable = false, updatable = false) private UUID packId;
    @Column(name = "shipment_id") private UUID shipmentId;
    @Column(name = "package_no", nullable = false, updatable = false) private int packageNo;
    @Column(name = "label_code", nullable = false, updatable = false, length = 48) private String labelCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16) private PackageStatus status;
    @Column(name = "weight_kg", precision = 10, scale = 3) private BigDecimal weightKg;
    @Column(name = "length_cm", precision = 10, scale = 2) private BigDecimal lengthCm;
    @Column(name = "width_cm", precision = 10, scale = 2) private BigDecimal widthCm;
    @Column(name = "height_cm", precision = 10, scale = 2) private BigDecimal heightCm;
    @Column(name = "dim_weight_kg", precision = 10, scale = 3) private BigDecimal dimWeightKg;
    @Column(name = "rotatable", nullable = false) private boolean rotatable;
    @Column(name = "rotatable_overridden", nullable = false) private boolean rotatableOverridden;
    @Column(name = "sealed_at") private Instant sealedAt;
    @Column(name = "sealed_by") private UUID sealedBy;
    @Column(name = "voided_at") private Instant voidedAt;
    @Column(name = "voided_by") private UUID voidedBy;
    /** 4C-6: the pick line that took this shipping carton; null for a repack parcel. Fixed. */
    @Column(name = "pick_line_id", updatable = false) private UUID pickLineId;
    /** 4C-6: the carton's LPN - the shipping carton itself (kltn-docs 08 BR-02). Fixed. */
    @Column(name = "lpn", updatable = false, length = 30) private String lpn;
    @Column(name = "checked_at") private Instant checkedAt;
    @Column(name = "checked_by") private UUID checkedBy;
    @Column(name = "labeled_at") private Instant labeledAt;
    @Column(name = "labeled_by") private UUID labeledBy;
    /** 4C-6: 0 before the first shipping label, then up by one per reprint; only the latest is valid (08 BR-08). */
    @Column(name = "label_version", nullable = false) private int labelVersion;
    @Column(name = "removed_at") private Instant removedAt;
    @Column(name = "removed_by") private UUID removedBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "removal_reason", length = 16) private CartonCheck.RemovalReason removalReason;
    @Column(name = "removal_note", length = 500) private String removalNote;

    protected PackageJpaEntity() {
    }

    public PackageJpaEntity(UUID id, UUID packId, int packageNo, String labelCode) {
        super(id);
        this.packId = packId;
        this.packageNo = packageNo;
        this.labelCode = labelCode;
        this.status = PackageStatus.OPEN;
        this.rotatable = true;
    }

    /** A shipping carton the check station received from a picked pick line (4C-6): TO_CHECK, one CARTON. */
    public static PackageJpaEntity carton(UUID id, UUID packId, int packageNo, String labelCode, UUID pickLineId,
                                          String lpn) {
        PackageJpaEntity row = new PackageJpaEntity(id, packId, packageNo, labelCode);
        row.pickLineId = pickLineId;
        row.lpn = lpn;
        row.status = PackageStatus.TO_CHECK;
        return row;
    }

    /** What the check station decided about a shipping carton (4C-6). */
    @SuppressWarnings("java:S107")
    public void applyCarton(PackageStatus status, Instant checkedAt, UUID checkedBy, Instant labeledAt,
                            UUID labeledBy, int labelVersion, BigDecimal weightKg, BigDecimal lengthCm,
                            BigDecimal widthCm, BigDecimal heightCm, BigDecimal dimWeightKg, boolean rotatable,
                            UUID shipmentId, Instant removedAt, UUID removedBy,
                            CartonCheck.RemovalReason removalReason, String removalNote) {
        this.status = status;
        this.checkedAt = checkedAt;
        this.checkedBy = checkedBy;
        this.labeledAt = labeledAt;
        this.labeledBy = labeledBy;
        this.labelVersion = labelVersion;
        this.weightKg = weightKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.dimWeightKg = dimWeightKg;
        this.rotatable = rotatable;
        this.shipmentId = shipmentId;
        this.removedAt = removedAt;
        this.removedBy = removedBy;
        this.removalReason = removalReason;
        this.removalNote = removalNote;
    }

    /**
     * A labelled carton takes its SKU's corrected carton spec as its shipment is planned (4C-6, 08 edge case "the real
     * carton differs from the declared spec"); nothing else about it changes.
     */
    public void remeasure(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                          BigDecimal dimWeightKg, boolean rotatable) {
        this.weightKg = weightKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.dimWeightKg = dimWeightKg;
        this.rotatable = rotatable;
    }

    public UUID getPickLineId() { return pickLineId; }
    public String getLpn() { return lpn; }
    public Instant getCheckedAt() { return checkedAt; }
    public UUID getCheckedBy() { return checkedBy; }
    public Instant getLabeledAt() { return labeledAt; }
    public UUID getLabeledBy() { return labeledBy; }
    public int getLabelVersion() { return labelVersion; }
    public Instant getRemovedAt() { return removedAt; }
    public UUID getRemovedBy() { return removedBy; }
    public CartonCheck.RemovalReason getRemovalReason() { return removalReason; }
    public String getRemovalNote() { return removalNote; }

    /** Whether this row is a shipping carton of the check station rather than a repack parcel. */
    public boolean isCarton() { return pickLineId != null; }

    /**
     * The parcel moves to another shipment: a load plan left it behind and its shipment was split (SCRUM-392). A
     * shipping carton's label names neither the shipment nor n/N (4C-6), so nothing is to be reprinted.
     */
    public void moveToShipment(UUID shipmentId) {
        this.shipmentId = shipmentId;
    }

    public UUID getPackId() { return packId; }
    public UUID getShipmentId() { return shipmentId; }
    public int getPackageNo() { return packageNo; }
    public String getLabelCode() { return labelCode; }
    public PackageStatus getStatus() { return status; }
    public BigDecimal getWeightKg() { return weightKg; }
    public BigDecimal getLengthCm() { return lengthCm; }
    public BigDecimal getWidthCm() { return widthCm; }
    public BigDecimal getHeightCm() { return heightCm; }
    public BigDecimal getDimWeightKg() { return dimWeightKg; }
    public boolean isRotatable() { return rotatable; }
    public boolean isRotatableOverridden() { return rotatableOverridden; }
    public Instant getSealedAt() { return sealedAt; }
    public UUID getSealedBy() { return sealedBy; }
    public Instant getVoidedAt() { return voidedAt; }
    public UUID getVoidedBy() { return voidedBy; }
}

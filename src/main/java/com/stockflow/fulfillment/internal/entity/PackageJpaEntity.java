package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.KitPart;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PackageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a parcel (table {@code fulfillment.package}). Not the domain model: {@code PackingSession.Parcel}
 * holds the rules; the repository adapter copies between the two. {@code tracking_number} and {@code label_url} are
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
    @Column(name = "package_type", nullable = false, length = 16) private PackageType packageType;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16) private PackageStatus status;
    @Column(name = "weight_kg", precision = 10, scale = 3) private BigDecimal weightKg;
    @Column(name = "length_cm", precision = 10, scale = 2) private BigDecimal lengthCm;
    @Column(name = "width_cm", precision = 10, scale = 2) private BigDecimal widthCm;
    @Column(name = "height_cm", precision = 10, scale = 2) private BigDecimal heightCm;
    @Column(name = "dim_weight_kg", precision = 10, scale = 3) private BigDecimal dimWeightKg;
    @Column(name = "rotatable", nullable = false) private boolean rotatable;
    @Column(name = "rotatable_overridden", nullable = false) private boolean rotatableOverridden;
    @Column(name = "fragile", nullable = false) private boolean fragile;
    @Column(name = "fragile_overridden", nullable = false) private boolean fragileOverridden;
    @Column(name = "sealed_at") private Instant sealedAt;
    @Column(name = "sealed_by") private UUID sealedBy;
    @Column(name = "voided_at") private Instant voidedAt;
    @Column(name = "voided_by") private UUID voidedBy;
    /** SCRUM-287 LP2-6: the kit ("bộ") it is a part of; all three null for an ordinary parcel. Fixed at opening. */
    @Column(name = "kit_group_id", updatable = false) private UUID kitGroupId;
    @Column(name = "kit_part_no", updatable = false) private Short kitPartNo;
    @Column(name = "kit_part_count", updatable = false) private Short kitPartCount;
    @Column(name = "kit_order_line_id", updatable = false) private UUID kitOrderLineId;
    /** SCRUM-287 PR T: the transfer line a transfer's kit holds a unit of (kit_order_line_id for an order's). */
    @Column(name = "kit_transfer_line_id", updatable = false) private UUID kitTransferLineId;
    /** SCRUM-287 PR T: the transfer order this parcel carries (its pack's); never with a shipment. */
    @Column(name = "transfer_order_id", updatable = false) private UUID transferOrderId;
    /** LP2-8: the unopened stock carton shipped as it is in this parcel (a transfer's only). */
    @Column(name = "stock_package_id") private UUID stockPackageId;
    /** KIT-FORCE: why this ordinary parcel holds units of an item that ships as several boxes. */
    @Column(name = "kit_override_reason", length = 500) private String kitOverrideReason;
    /** SCRUM-392: moved to another shipment since its label was printed; reprint before loading. */
    @Column(name = "label_outdated_at") private Instant labelOutdatedAt;

    protected PackageJpaEntity() {
    }

    public PackageJpaEntity(UUID id, UUID packId, int packageNo, String labelCode) {
        super(id);
        this.packId = packId;
        this.packageNo = packageNo;
        this.labelCode = labelCode;
        this.packageType = PackageType.CARTON;
        this.status = PackageStatus.OPEN;
        this.rotatable = true;
    }

    /** A part of a kit (LP2-6): its group, part number and the kit's size are fixed when the kit is opened. */
    public PackageJpaEntity(UUID id, UUID packId, int packageNo, String labelCode, KitPart kit) {
        this(id, packId, packageNo, labelCode, kit, null);
    }

    /**
     * A parcel of a transfer's packing session when {@code transferOrderId} is given (SCRUM-287 PR T): its kit, if any,
     * holds a unit of a transfer line ({@link KitPart#orderLineId} is then that line's id).
     */
    public PackageJpaEntity(UUID id, UUID packId, int packageNo, String labelCode, KitPart kit, UUID transferOrderId) {
        this(id, packId, packageNo, labelCode);
        this.transferOrderId = transferOrderId;
        if (kit != null) {
            this.kitGroupId = kit.groupId();
            this.kitPartNo = (short) kit.partNo();
            this.kitPartCount = (short) kit.partCount();
            if (transferOrderId == null) {
                this.kitOrderLineId = kit.orderLineId();
            } else {
                this.kitTransferLineId = kit.orderLineId();
            }
        }
    }

    @SuppressWarnings("java:S107")
    public void apply(UUID shipmentId, PackageType type, PackageStatus status, BigDecimal weightKg,
                      BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal dimWeightKg,
                      boolean rotatable, boolean rotatableOverridden, boolean fragile, boolean fragileOverridden,
                      Instant sealedAt, UUID sealedBy, Instant voidedAt, UUID voidedBy) {
        this.shipmentId = shipmentId;
        this.packageType = type;
        this.status = status;
        this.weightKg = weightKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.dimWeightKg = dimWeightKg;
        this.rotatable = rotatable;
        this.rotatableOverridden = rotatableOverridden;
        this.fragile = fragile;
        this.fragileOverridden = fragileOverridden;
        this.sealedAt = sealedAt;
        this.sealedBy = sealedBy;
        this.voidedAt = voidedAt;
        this.voidedBy = voidedBy;
    }

    public UUID getPackId() { return packId; }
    public UUID getTransferOrderId() { return transferOrderId; }
    public UUID getStockPackageId() { return stockPackageId; }

    /** LP2-8: the unopened stock carton this parcel ships as it is. */
    public void shipsCarton(UUID stockPackageId) { this.stockPackageId = stockPackageId; }
    public UUID getShipmentId() { return shipmentId; }
    public int getPackageNo() { return packageNo; }
    public String getLabelCode() { return labelCode; }
    public Instant getLabelOutdatedAt() { return labelOutdatedAt; }

    /** SCRUM-392: the label names another shipment than the parcel's now (set), or was reprinted (null). */
    public void markLabelOutdated(Instant at) {
        this.labelOutdatedAt = at;
    }
    public PackageType getPackageType() { return packageType; }
    public PackageStatus getStatus() { return status; }
    public BigDecimal getWeightKg() { return weightKg; }
    public BigDecimal getLengthCm() { return lengthCm; }
    public BigDecimal getWidthCm() { return widthCm; }
    public BigDecimal getHeightCm() { return heightCm; }
    public BigDecimal getDimWeightKg() { return dimWeightKg; }
    public boolean isRotatable() { return rotatable; }
    public boolean isRotatableOverridden() { return rotatableOverridden; }
    public boolean isFragile() { return fragile; }
    public boolean isFragileOverridden() { return fragileOverridden; }
    public Instant getSealedAt() { return sealedAt; }
    public UUID getSealedBy() { return sealedBy; }
    public Instant getVoidedAt() { return voidedAt; }
    public UUID getVoidedBy() { return voidedBy; }

    public String getKitOverrideReason() { return kitOverrideReason; }

    public void setKitOverrideReason(String kitOverrideReason) { this.kitOverrideReason = kitOverrideReason; }

    /** The kit part it is, or null for an ordinary parcel. */
    public KitPart getKit() {
        return kitGroupId == null ? null : new KitPart(kitGroupId, kitPartNo, kitPartCount,
                kitOrderLineId != null ? kitOrderLineId : kitTransferLineId);
    }
}

package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A shipment on a load's manifest (table {@code fulfillment.dispatch_load_shipment}). */
@Entity
@Table(name = "dispatch_load_shipment", schema = "fulfillment")
public class DispatchLoadShipmentJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "shipment_id", updatable = false) private UUID shipmentId;
    /** SCRUM-287 PR T: the transfer order on a TRANSFER plan's vehicle (shipment_id null then). */
    @Column(name = "transfer_order_id", updatable = false) private UUID transferOrderId;
    /** The load's delivery mode (V20261022001400): a PARCEL manifest holds a shipment alone, an FTL one shares it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false, length = 16) private DeliveryMode mode;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "added_at", nullable = false) private Instant addedAt;
    @Column(name = "added_by", nullable = false) private UUID addedBy;
    @Column(name = "removed_at") private Instant removedAt;
    @Column(name = "removed_by") private UUID removedBy;
    @Column(name = "label_cod_amount", precision = 19, scale = 4) private BigDecimal labelCodAmount;
    @Column(name = "cod_amount", precision = 19, scale = 4) private BigDecimal codAmount;

    protected DispatchLoadShipmentJpaEntity() {
    }

    public DispatchLoadShipmentJpaEntity(UUID id, UUID loadId, UUID shipmentId, DeliveryMode mode) {
        this(id, loadId, shipmentId, mode, false);
    }

    /** @param transfer {@code key} is a transfer order (a TRANSFER plan's vehicle, SCRUM-287 PR T), not a shipment */
    public DispatchLoadShipmentJpaEntity(UUID id, UUID loadId, UUID key, DeliveryMode mode, boolean transfer) {
        super(id);
        this.loadId = loadId;
        this.shipmentId = transfer ? null : key;
        this.transferOrderId = transfer ? key : null;
        this.mode = mode;
    }

    public void apply(boolean active, Instant addedAt, UUID addedBy, Instant removedAt, UUID removedBy,
                      BigDecimal labelCodAmount, BigDecimal codAmount) {
        this.active = active;
        this.addedAt = addedAt;
        this.addedBy = addedBy;
        this.removedAt = removedAt;
        this.removedBy = removedBy;
        this.labelCodAmount = labelCodAmount;
        this.codAmount = codAmount;
    }

    public UUID getLoadId() { return loadId; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getTransferOrderId() { return transferOrderId; }

    /** What is on the manifest: the shipment, or the transfer order. */
    public UUID getKey() { return shipmentId != null ? shipmentId : transferOrderId; }
    public DeliveryMode getMode() { return mode; }
    public boolean isActive() { return active; }
    public Instant getAddedAt() { return addedAt; }
    public UUID getAddedBy() { return addedBy; }
    public Instant getRemovedAt() { return removedAt; }
    public UUID getRemovedBy() { return removedBy; }
    public BigDecimal getLabelCodAmount() { return labelCodAmount; }
    public BigDecimal getCodAmount() { return codAmount; }
}

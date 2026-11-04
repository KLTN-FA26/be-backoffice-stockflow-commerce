package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.DeliveryFailureReason;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a shipment (table {@code fulfillment.shipment}). Not the domain model: {@link Shipment} holds the
 * 09 §5 lifecycle; {@link #toDomain} and {@link #apply} copy between the two. The legacy carrier columns
 * ({@code carrier}, {@code tracking_number}, {@code dispatched_at}) are not mapped: no carrier is called (09 §1); the
 * carrier is {@code carrier_id} from the catalogue (G4-2a), the free text was carried over by V20261021000400.
 */
@Entity
@Table(name = "shipment", schema = "fulfillment")
public class ShipmentJpaEntity extends BaseEntity {

    @Column(name = "pack_id", updatable = false) private UUID packId;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "code", nullable = false, updatable = false, length = 48) private String code;
    @Column(name = "request_id", updatable = false) private UUID requestId;
    @Column(name = "warehouse_id", nullable = false, updatable = false) private UUID warehouseId;
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 16) private DeliveryMode deliveryMode;
    @Column(name = "package_count", nullable = false) private int packageCount;
    @Column(name = "cod_amount", nullable = false, precision = 19, scale = 4) private BigDecimal codAmount;
    @Column(name = "partial", nullable = false, updatable = false) private boolean partial;
    @Column(name = "partial_note", updatable = false, length = 500) private String partialNote;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32) private ShipmentStatus status;
    @Column(name = "ready_at", nullable = false, updatable = false) private Instant readyAt;
    @Column(name = "ready_by", updatable = false) private UUID readyBy;
    @Column(name = "handed_over_at") private Instant handedOverAt;
    @Column(name = "handed_over_by") private UUID handedOverBy;
    @Column(name = "outcome_at") private Instant outcomeAt;
    @Column(name = "outcome_by") private UUID outcomeBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 24) private DeliveryFailureReason failureReason;
    @Column(name = "failure_note", length = 500) private String failureNote;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancel_reason", length = 500) private String cancelReason;
    @Column(name = "carrier_id") private UUID carrierId;
    @Column(name = "load_id") private UUID loadId;
    @Column(name = "whole_order_handover", nullable = false) private boolean wholeOrderHandover;
    @Column(name = "label_cod_amount", nullable = false, precision = 19, scale = 4) private BigDecimal labelCodAmount;
    @Column(name = "label_printed_at") private Instant labelPrintedAt;

    protected ShipmentJpaEntity() {
    }

    /** A new row for a shipment just born. */
    public static ShipmentJpaEntity of(Shipment shipment) {
        Shipment.Snapshot s = shipment.snapshot();
        ShipmentJpaEntity entity = new ShipmentJpaEntity(s.id());
        entity.packId = s.packId();
        entity.orderId = s.orderId();
        entity.code = s.code();
        entity.requestId = s.requestId();
        entity.warehouseId = s.warehouseId();
        entity.partial = s.partial();
        entity.partialNote = s.partialNote();
        entity.readyAt = s.readyAt();
        entity.readyBy = s.readyBy();
        entity.apply(shipment);
        return entity;
    }

    private ShipmentJpaEntity(UUID id) {
        super(id);
    }

    /** What may change after the shipment was born. */
    public void apply(Shipment shipment) {
        Shipment.Snapshot s = shipment.snapshot();
        this.deliveryMode = s.deliveryMode();
        this.packageCount = s.packageCount();
        this.codAmount = s.codAmount();
        this.status = s.status();
        this.handedOverAt = s.handedOverAt();
        this.handedOverBy = s.handedOverBy();
        this.outcomeAt = s.outcomeAt();
        this.outcomeBy = s.outcomeBy();
        this.failureReason = s.failureReason();
        this.failureNote = s.failureNote();
        this.cancelledAt = s.cancelledAt();
        this.cancelReason = s.cancelReason();
        this.carrierId = s.carrierId();
        this.loadId = s.loadId();
        this.wholeOrderHandover = s.wholeOrderHandover();
        this.labelCodAmount = s.labelCodAmount();
        this.labelPrintedAt = s.labelPrintedAt();
    }

    public Shipment toDomain() {
        return Shipment.restore(new Shipment.Snapshot(getId(), code, orderId, packId, warehouseId, requestId,
                deliveryMode, packageCount, codAmount, partial, partialNote, status, readyAt, readyBy, handedOverAt,
                handedOverBy, outcomeAt, outcomeBy, failureReason, failureNote, cancelledAt, cancelReason, carrierId,
                loadId, wholeOrderHandover, labelCodAmount, labelPrintedAt));
    }

    public UUID getPackId() { return packId; }
    public UUID getOrderId() { return orderId; }
    public String getCode() { return code; }
    public UUID getRequestId() { return requestId; }
    public UUID getWarehouseId() { return warehouseId; }
    public DeliveryMode getDeliveryMode() { return deliveryMode; }
    public int getPackageCount() { return packageCount; }
    public BigDecimal getCodAmount() { return codAmount; }
    public boolean isPartial() { return partial; }
    public String getPartialNote() { return partialNote; }
    public ShipmentStatus getStatus() { return status; }
    public Instant getReadyAt() { return readyAt; }
    public UUID getReadyBy() { return readyBy; }
    public UUID getCarrierId() { return carrierId; }
    public UUID getLoadId() { return loadId; }
    public boolean isWholeOrderHandover() { return wholeOrderHandover; }
    public BigDecimal getLabelCodAmount() { return labelCodAmount; }
    public Instant getLabelPrintedAt() { return labelPrintedAt; }
    public Instant getHandedOverAt() { return handedOverAt; }
    public UUID getHandedOverBy() { return handedOverBy; }
    public Instant getOutcomeAt() { return outcomeAt; }
    public DeliveryFailureReason getFailureReason() { return failureReason; }
    public String getFailureNote() { return failureNote; }
    public Instant getCancelledAt() { return cancelledAt; }
}

package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code inventory.stock_movement}, the ledger. Rows are inserted and never updated, so there are
 * no setters; {@code version} exists only because every table carries {@code BaseEntity}'s columns.
 */
@Entity
@Table(name = "stock_movement", schema = "inventory")
public class StockMovementJpaEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 24, updatable = false)
    private StockMovement.MovementType movementType;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "from_location_code", length = 64, updatable = false)
    private String fromLocationCode;

    @Column(name = "to_location_code", length = 64, updatable = false)
    private String toLocationCode;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32, updatable = false)
    private StockStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32, updatable = false)
    private StockStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 32, updatable = false)
    private StockMovement.ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StockMovementJpaEntity() {
    }

    public StockMovementJpaEntity(UUID id, StockMovement.MovementType movementType, String sku,
                                  String lotNumber, String fromLocationCode, String toLocationCode,
                                  int quantity, StockStatus fromStatus, StockStatus toStatus,
                                  StockMovement.ReferenceType referenceType, UUID referenceId,
                                  String reason, UUID actorId, Instant occurredAt) {
        super(id);
        this.movementType = movementType;
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.fromLocationCode = fromLocationCode;
        this.toLocationCode = toLocationCode;
        this.quantity = quantity;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    public StockMovement.MovementType getMovementType() { return movementType; }
    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public String getFromLocationCode() { return fromLocationCode; }
    public String getToLocationCode() { return toLocationCode; }
    public int getQuantity() { return quantity; }
    public StockStatus getFromStatus() { return fromStatus; }
    public StockStatus getToStatus() { return toStatus; }
    public StockMovement.ReferenceType getReferenceType() { return referenceType; }
    public UUID getReferenceId() { return referenceId; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
}

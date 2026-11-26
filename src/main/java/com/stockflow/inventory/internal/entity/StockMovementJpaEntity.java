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

    /** The lot moved (SCRUM-142); written as null when the application leaves it to {@code tg_stock_movement_lot}. */
    @Column(name = "lot_id", updatable = false)
    private UUID lotId;

    /** The customer order a pick, put-back or package deduction was for (SCRUM-153). */
    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    /**
     * The carton the line is about (ADR-0011): one column where there were two sides (V20261230000200), and the whole
     * of what the line moved since the ledger keeps no quantity (V20261230000400) - its units are the carton's
     * {@code nominal_qty}.
     */
    @Column(name = "stock_package_id", nullable = false, updatable = false)
    private UUID stockPackageId;

    /** WF-05 fix plan, section 8: who asked for the adjustment an ADJUSTMENT line posts; null on any other line. */
    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    /** Who counted the line, when that adjustment is a cycle count's variance. */
    @Column(name = "counted_by", updatable = false)
    private UUID countedBy;

    protected StockMovementJpaEntity() {
    }

    /** The line posts an adjustment {@code requestedBy} asked for and, for a count's variance, {@code countedBy} counted. */
    public StockMovementJpaEntity askedBy(UUID requestedBy, UUID countedBy) {
        this.requestedBy = requestedBy;
        this.countedBy = countedBy;
        return this;
    }

    public UUID getRequestedBy() { return requestedBy; }
    public UUID getCountedBy() { return countedBy; }

    public StockMovementJpaEntity(UUID id, StockMovement.MovementType movementType, String sku,
                                  String lotNumber, String fromLocationCode, String toLocationCode,
                                  StockStatus fromStatus, StockStatus toStatus,
                                  StockMovement.ReferenceType referenceType, UUID referenceId,
                                  String reason, UUID actorId, Instant occurredAt, UUID lotId, UUID orderId,
                                  UUID stockPackageId) {
        this(id, movementType, sku, lotNumber, fromLocationCode, toLocationCode, fromStatus, toStatus,
                referenceType, referenceId, reason, actorId, occurredAt);
        this.lotId = lotId;
        this.orderId = orderId;
        this.stockPackageId = stockPackageId;
    }

    public UUID getStockPackageId() { return stockPackageId; }

    public StockMovementJpaEntity(UUID id, StockMovement.MovementType movementType, String sku,
                                  String lotNumber, String fromLocationCode, String toLocationCode,
                                  StockStatus fromStatus, StockStatus toStatus,
                                  StockMovement.ReferenceType referenceType, UUID referenceId,
                                  String reason, UUID actorId, Instant occurredAt) {
        super(id);
        this.movementType = movementType;
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.fromLocationCode = fromLocationCode;
        this.toLocationCode = toLocationCode;
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
    public StockStatus getFromStatus() { return fromStatus; }
    public StockStatus getToStatus() { return toStatus; }
    public StockMovement.ReferenceType getReferenceType() { return referenceType; }
    public UUID getReferenceId() { return referenceId; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getLotId() { return lotId; }
    public UUID getOrderId() { return orderId; }
}

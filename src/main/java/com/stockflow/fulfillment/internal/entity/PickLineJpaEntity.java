package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.domain.ShortPickReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a pick line (table {@code fulfillment.pick_line}). Not the domain model: behaviour is
 * on {@code PickList}/{@code PickLine}, and {@code PickLineMapper} copies between the two.
 *
 * <p>The pick is referenced by id, not by a {@code @ManyToOne}: the lines are always read for one
 * known task, and an association would only add a lazy proxy to load.</p>
 */
@Entity
@Table(name = "pick_line", schema = "fulfillment")
public class PickLineJpaEntity extends BaseEntity {

    @Column(name = "pick_id", nullable = false, updatable = false)
    private UUID pickId;
    @Column(name = "order_line_id", nullable = false, updatable = false)
    private UUID orderLineId;
    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;
    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;
    @Column(name = "location_code", nullable = false, length = 64, updatable = false)
    private String locationCode;
    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;
    @Column(name = "allocated_qty", nullable = false, updatable = false)
    private int allocatedQty;
    @Column(name = "picked_qty", nullable = false)
    private int pickedQty;
    @Column(name = "short_qty", nullable = false)
    private int shortQty;
    /** 4C-4: why the carton could not be taken; {@code ck_pick_line_short_reason}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "short_reason", length = 16)
    private ShortPickReason shortReason;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PickLineStatus status;
    @Column(name = "picked_by")
    private UUID pickedBy;
    @Column(name = "picked_at")
    private Instant pickedAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "scanned_location_code", length = 64)
    private String scannedLocationCode;
    @Column(name = "scanned_code", length = 64)
    private String scannedCode;
    @Column(name = "put_back_qty", nullable = false)
    private int putBackQty;
    @Column(name = "put_back_location_code", length = 64)
    private String putBackLocationCode;
    @Column(name = "put_back_by")
    private UUID putBackBy;
    @Column(name = "put_back_at")
    private Instant putBackAt;
    @Column(name = "lpn", length = 30, updatable = false)
    private String lpn;
    @Column(name = "whole_carton", nullable = false, updatable = false)
    private boolean wholeCarton;
    /** 4C-5: when the carton was moved to the staging area; {@code ck_pick_line_staged}. */
    @Column(name = "staged_at")
    private Instant stagedAt;
    /** 4C-5: the staging area it was moved to. */
    @Column(name = "staged_location_code", length = 64)
    private String stagedLocationCode;

    protected PickLineJpaEntity() {
    }

    public PickLineJpaEntity(UUID id, UUID pickId, UUID orderLineId, UUID reservationId, String sku,
                             String locationCode, String lotNumber, int sequenceNo, int allocatedQty) {
        super(id);
        this.pickId = pickId;
        this.orderLineId = orderLineId;
        this.reservationId = reservationId;
        this.sku = sku;
        this.locationCode = locationCode;
        this.lotNumber = lotNumber;
        this.sequenceNo = sequenceNo;
        this.allocatedQty = allocatedQty;
        this.status = PickLineStatus.OPEN;
    }

    /** A line drawn from the carton {@code lpn}; tg_pick_line_carton checks it is its hold's carton. */
    public PickLineJpaEntity(UUID id, UUID pickId, UUID orderLineId, UUID reservationId, String sku,
                             String locationCode, String lotNumber, int sequenceNo, int allocatedQty, String lpn,
                             boolean wholeCarton) {
        this(id, pickId, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty);
        this.lpn = lpn;
        this.wholeCarton = wholeCarton;
    }

    /** Copies what a line's life changes: everything but its identity and its allocation. */
    public void apply(int pickedQty, PickLineStatus status, UUID pickedBy, Instant pickedAt, Instant consumedAt,
                      String scannedLocationCode, String scannedCode, int putBackQty, String putBackLocationCode,
                      UUID putBackBy, Instant putBackAt) {
        this.pickedQty = pickedQty;
        this.status = status;
        this.pickedBy = pickedBy;
        this.pickedAt = pickedAt;
        this.consumedAt = consumedAt;
        this.scannedLocationCode = scannedLocationCode;
        this.scannedCode = scannedCode;
        this.putBackQty = putBackQty;
        this.putBackLocationCode = putBackLocationCode;
        this.putBackBy = putBackBy;
        this.putBackAt = putBackAt;
    }

    /**
     * What a picker could not take (X-SHORT) and why; {@code ck_pick_line_within_allocation} bounds the units and
     * {@code ck_pick_line_short_reason} ties a reason to the whole line.
     */
    public void recordShort(int shortQty, ShortPickReason shortReason) {
        this.shortQty = shortQty;
        this.shortReason = shortReason;
    }

    /** When and where the carton was staged (4C-5); {@code ck_pick_line_staged} keeps the two together. */
    public void recordStaged(Instant stagedAt, String stagedLocationCode) {
        this.stagedAt = stagedAt;
        this.stagedLocationCode = stagedLocationCode;
    }

    public Instant getStagedAt() { return stagedAt; }
    public String getStagedLocationCode() { return stagedLocationCode; }

    public UUID getPickId() { return pickId; }
    public UUID getOrderLineId() { return orderLineId; }
    public UUID getReservationId() { return reservationId; }
    public String getSku() { return sku; }
    public String getLocationCode() { return locationCode; }
    public String getLotNumber() { return lotNumber; }
    public int getSequenceNo() { return sequenceNo; }
    public int getAllocatedQty() { return allocatedQty; }
    public int getPickedQty() { return pickedQty; }
    public int getShortQty() { return shortQty; }
    public ShortPickReason getShortReason() { return shortReason; }
    public String getLpn() { return lpn; }
    public boolean isWholeCarton() { return wholeCarton; }
    public PickLineStatus getStatus() { return status; }
    public UUID getPickedBy() { return pickedBy; }
    public Instant getPickedAt() { return pickedAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public String getScannedLocationCode() { return scannedLocationCode; }
    public String getScannedCode() { return scannedCode; }
    public int getPutBackQty() { return putBackQty; }
    public String getPutBackLocationCode() { return putBackLocationCode; }
    public UUID getPutBackBy() { return putBackBy; }
    public Instant getPutBackAt() { return putBackAt; }
}

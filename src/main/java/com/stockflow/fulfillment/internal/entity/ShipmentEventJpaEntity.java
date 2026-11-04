package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One step of a shipment's timeline (table {@code fulfillment.shipment_event}, append-only). Steps taken in the
 * system are {@code MANUAL} and name who recorded them (09 BR-02); carrier feeds are not used (09 §1).
 */
@Entity
@Table(name = "shipment_event", schema = "fulfillment")
public class ShipmentEventJpaEntity extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "status", nullable = false, updatable = false, length = 24) private String status;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(name = "source", nullable = false, updatable = false, length = 16) private String source;
    @Column(name = "note", updatable = false, length = 1000) private String note;
    @Column(name = "recorded_by", updatable = false) private UUID recordedBy;

    protected ShipmentEventJpaEntity() {
    }

    public ShipmentEventJpaEntity(UUID id, UUID shipmentId, String status, Instant occurredAt, String note,
                                  UUID recordedBy) {
        super(id);
        this.shipmentId = shipmentId;
        this.status = status;
        this.occurredAt = occurredAt;
        this.source = "MANUAL";
        this.note = note;
        this.recordedBy = recordedBy;
    }

    public UUID getShipmentId() { return shipmentId; }
    public String getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getNote() { return note; }
    public UUID getRecordedBy() { return recordedBy; }
}

package com.stockflow.fulfillment.internal.entity;

import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a shipment (table {@code fulfillment.shipment}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "shipment", schema = "fulfillment")
public class ShipmentJpaEntity extends BaseEntity {

    @Column(name = "pack_id")
    private UUID packId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "carrier", length = 120)
    private String carrier;

    @Column(name = "tracking_number", length = 128)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ShipmentStatus status;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    protected ShipmentJpaEntity() {
    }

    public ShipmentJpaEntity(UUID id, UUID packId, UUID orderId, String carrier,
                             String trackingNumber, ShipmentStatus status, Instant dispatchedAt) {
        super(id);
        this.packId = packId;
        this.orderId = orderId;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = status;
        this.dispatchedAt = dispatchedAt;
    }

    public UUID getPackId() { return packId; }
    public UUID getOrderId() { return orderId; }
    public String getCarrier() { return carrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public ShipmentStatus getStatus() { return status; }
    public Instant getDispatchedAt() { return dispatchedAt; }
}

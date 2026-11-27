package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A booking taken off a load (table {@code fulfillment.load_booking_cancellation}; kltn-docs 09 §4.2 step 6: the
 * carrier, waybill number, plate and driver leave the load "nhưng vẫn lưu trong lịch sử"). Append-only: every column
 * is insert-only. {@code cancelledBy} is null when the system cancelled it.
 */
@Entity
@Table(name = "load_booking_cancellation", schema = "fulfillment")
public class LoadBookingCancellationJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "carrier_id", nullable = false, updatable = false) private UUID carrierId;
    @Column(name = "waybill_no", length = 64, updatable = false) private String waybillNo;
    @Column(name = "vehicle_plate", length = 20, updatable = false) private String vehiclePlate;
    @Column(name = "driver_name", length = 120, updatable = false) private String driverName;
    @Column(name = "driver_phone", length = 32, updatable = false) private String driverPhone;
    @Column(name = "booked_at", updatable = false) private Instant bookedAt;
    @Column(name = "booked_by", updatable = false) private UUID bookedBy;
    @Column(name = "cancelled_at", nullable = false, updatable = false) private Instant cancelledAt;
    @Column(name = "cancelled_by", updatable = false) private UUID cancelledBy;
    @Column(name = "reason", nullable = false, length = 500, updatable = false) private String reason;

    protected LoadBookingCancellationJpaEntity() {
    }

    public LoadBookingCancellationJpaEntity(UUID id, UUID loadId, DispatchLoad.CancelledBooking b) {
        super(id);
        this.loadId = loadId;
        this.carrierId = b.carrierId();
        this.waybillNo = b.waybillNo();
        this.vehiclePlate = b.vehiclePlate();
        this.driverName = b.driverName();
        this.driverPhone = b.driverPhone();
        this.bookedAt = b.bookedAt();
        this.bookedBy = b.bookedBy();
        this.cancelledAt = b.cancelledAt();
        this.cancelledBy = b.cancelledBy();
        this.reason = b.reason();
    }

    public UUID getLoadId() { return loadId; }
}

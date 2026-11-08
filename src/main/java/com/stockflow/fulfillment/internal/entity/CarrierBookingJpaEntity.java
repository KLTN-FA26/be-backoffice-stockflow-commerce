package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One order created at a carrier for a load (table {@code fulfillment.carrier_booking}; kltn-docs 09 BR-29: one vehicle,
 * one order; the pickup is the warehouse, each shipment a drop-off). Its life: REQUESTED while the call is under way,
 * then BOOKED (the carrier's order id is the load's waybill number) or FAILED; a booked order ends CANCELLED, or
 * CANCEL_FAILED when the carrier refused to cancel it after the load was called off.
 */
@Entity
@Table(name = "carrier_booking", schema = "fulfillment")
public class CarrierBookingJpaEntity extends BaseEntity {

    public static final String REQUESTED = "REQUESTED";
    public static final String BOOKED = "BOOKED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String CANCEL_FAILED = "CANCEL_FAILED";

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "carrier_id", nullable = false, updatable = false) private UUID carrierId;
    @Column(name = "service_code", nullable = false, length = 64, updatable = false) private String serviceCode;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stops", nullable = false, columnDefinition = "jsonb", updatable = false)
    private List<Map<String, Object>> stops = new ArrayList<>();
    @Column(name = "order_time", updatable = false) private Instant orderTime;
    @Column(name = "status", nullable = false, length = 16) private String status;
    @Column(name = "carrier_order_id", length = 64) private String carrierOrderId;
    @Column(name = "tracking_link", length = 500) private String trackingLink;
    @Column(name = "requested_by", nullable = false, updatable = false) private UUID requestedBy;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "booked_at") private Instant bookedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancel_reason", length = 500) private String cancelReason;
    @Column(name = "cancel_attempts", nullable = false) private int cancelAttempts;
    @Column(name = "last_error", length = 1000) private String lastError;

    protected CarrierBookingJpaEntity() {
    }

    public CarrierBookingJpaEntity(UUID id, UUID loadId, UUID carrierId, String serviceCode,
                                   List<Map<String, Object>> stops, Instant orderTime, UUID requestedBy,
                                   Instant requestedAt) {
        super(id);
        this.loadId = loadId;
        this.carrierId = carrierId;
        this.serviceCode = serviceCode;
        this.stops = new ArrayList<>(stops);
        this.orderTime = orderTime;
        this.status = REQUESTED;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
    }

    /** The carrier created the order. */
    public void booked(String orderId, String link, Instant at) {
        this.status = BOOKED;
        this.carrierOrderId = orderId;
        this.trackingLink = link == null || link.length() <= 500 ? link : null;
        this.bookedAt = at;
        this.lastError = null;
    }

    /** The carrier refused or did not answer: nothing was created there. */
    public void failed(String error) {
        this.status = FAILED;
        this.lastError = cut(error);
    }

    /** Cancelled at the carrier. */
    public void cancelled(String reason, Instant at) {
        this.status = CANCELLED;
        this.cancelReason = reason == null ? null : reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.cancelledAt = at;
    }

    /** The carrier refused to cancel (or did not answer); counted, so a sweep gives up after a few tries. */
    public void cancelRefused(String error) {
        this.status = CANCEL_FAILED;
        this.cancelAttempts++;
        this.lastError = cut(error);
    }

    private static String cut(String error) {
        String text = error == null || error.isBlank() ? "no reason given" : error;
        return text.length() <= 1000 ? text : text.substring(0, 997) + "...";
    }

    public UUID getLoadId() { return loadId; }
    public UUID getCarrierId() { return carrierId; }
    public String getServiceCode() { return serviceCode; }
    public List<Map<String, Object>> getStops() { return stops; }
    public Instant getOrderTime() { return orderTime; }
    public String getStatus() { return status; }
    public String getCarrierOrderId() { return carrierOrderId; }
    public String getTrackingLink() { return trackingLink; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getBookedAt() { return bookedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancelReason() { return cancelReason; }
    public int getCancelAttempts() { return cancelAttempts; }
    public String getLastError() { return lastError; }
}

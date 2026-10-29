package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.UnloadReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a load's header (table {@code fulfillment.dispatch_load}). Not the domain model: {@link DispatchLoad}
 * holds the rules; the store copies between the two. {@code shipment_count} and {@code package_count} follow what is
 * on the manifest and the vehicle while it loads, so every scan moves the row's version: the version a dispatcher
 * signs against is the load as they saw it.
 */
@Entity
@Table(name = "dispatch_load", schema = "fulfillment")
public class DispatchLoadJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 30) private String code;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    @Column(name = "warehouse_id", nullable = false, updatable = false) private UUID warehouseId;
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false, length = 16) private DeliveryMode mode;
    @Column(name = "carrier_id") private UUID carrierId;
    @Column(name = "load_plan_vehicle_id") private UUID loadPlanVehicleId;
    @Column(name = "vehicle_plate", length = 20) private String vehiclePlate;
    @Column(name = "driver_name", length = 120) private String driverName;
    @Column(name = "driver_phone", length = 32) private String driverPhone;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16) private LoadStatus status;
    @Column(name = "booked_at") private Instant bookedAt;
    @Column(name = "booked_by") private UUID bookedBy;
    @Column(name = "booking_cancelled_at") private Instant bookingCancelledAt;
    @Column(name = "booking_cancelled_by") private UUID bookingCancelledBy;
    @Column(name = "booking_cancel_reason", length = 500) private String bookingCancelReason;
    @Column(name = "loading_started_at") private Instant loadingStartedAt;
    @Column(name = "loading_started_by") private UUID loadingStartedBy;
    @Column(name = "dispatched_at") private Instant dispatchedAt;
    @Column(name = "dispatched_by") private UUID dispatchedBy;
    @Column(name = "carrier_representative", length = 120) private String carrierRepresentative;
    @Column(name = "signature_ref", length = 255) private String signatureRef;
    @Column(name = "handover_note", length = 500) private String handoverNote;
    @Column(name = "shipment_count", nullable = false) private int shipmentCount;
    @Column(name = "package_count", nullable = false) private int packageCount;
    @Column(name = "cod_total", nullable = false, precision = 19, scale = 4) private BigDecimal codTotal;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancelled_by") private UUID cancelledBy;
    @Column(name = "cancel_reason", length = 500) private String cancelReason;
    /** SCRUM-474 UNLOAD-DESIGN point 7: set only by a controlled cancel (V20261029007000). */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason_code", length = 20) private UnloadReasonCode cancelReasonCode;

    protected DispatchLoadJpaEntity() {
    }

    public DispatchLoadJpaEntity(DispatchLoad.Snapshot s) {
        super(s.id());
        this.code = s.code();
        this.requestId = s.requestId();
        this.warehouseId = s.warehouseId();
        this.mode = s.mode();
    }

    /** Everything that may change after the load was opened. */
    public void apply(DispatchLoad.Snapshot s) {
        this.carrierId = s.carrierId();
        this.loadPlanVehicleId = s.loadPlanVehicleId();
        this.vehiclePlate = s.vehiclePlate();
        this.driverName = s.driverName();
        this.driverPhone = s.driverPhone();
        this.status = s.status();
        this.bookedAt = s.bookedAt();
        this.bookedBy = s.bookedBy();
        this.bookingCancelledAt = s.bookingCancelledAt();
        this.bookingCancelledBy = s.bookingCancelledBy();
        this.bookingCancelReason = s.bookingCancelReason();
        this.loadingStartedAt = s.loadingStartedAt();
        this.loadingStartedBy = s.loadingStartedBy();
        this.dispatchedAt = s.dispatchedAt();
        this.dispatchedBy = s.dispatchedBy();
        this.carrierRepresentative = s.carrierRepresentative();
        this.signatureRef = s.signatureRef();
        this.handoverNote = s.handoverNote();
        this.shipmentCount = (int) s.members().stream().filter(DispatchLoad.Member::active).count();
        this.packageCount = (int) s.loaded().stream().filter(DispatchLoad.Loaded::active).count();
        this.codTotal = s.codTotal();
        this.cancelledAt = s.cancelledAt();
        this.cancelledBy = s.cancelledBy();
        this.cancelReason = s.cancelReason();
        this.cancelReasonCode = s.cancelReasonCode();
    }

    public String getCode() { return code; }
    public UUID getRequestId() { return requestId; }
    public UUID getWarehouseId() { return warehouseId; }
    public DeliveryMode getMode() { return mode; }
    public UUID getCarrierId() { return carrierId; }
    public UUID getLoadPlanVehicleId() { return loadPlanVehicleId; }
    public String getVehiclePlate() { return vehiclePlate; }
    public String getDriverName() { return driverName; }
    public String getDriverPhone() { return driverPhone; }
    public LoadStatus getStatus() { return status; }
    public Instant getBookedAt() { return bookedAt; }
    public UUID getBookedBy() { return bookedBy; }
    public Instant getBookingCancelledAt() { return bookingCancelledAt; }
    public UUID getBookingCancelledBy() { return bookingCancelledBy; }
    public String getBookingCancelReason() { return bookingCancelReason; }
    public Instant getLoadingStartedAt() { return loadingStartedAt; }
    public UUID getLoadingStartedBy() { return loadingStartedBy; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public UUID getDispatchedBy() { return dispatchedBy; }
    public String getCarrierRepresentative() { return carrierRepresentative; }
    public String getSignatureRef() { return signatureRef; }
    public String getHandoverNote() { return handoverNote; }
    public int getShipmentCount() { return shipmentCount; }
    public int getPackageCount() { return packageCount; }
    public BigDecimal getCodTotal() { return codTotal; }
    public Instant getCancelledAt() { return cancelledAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public String getCancelReason() { return cancelReason; }
    public UnloadReasonCode getCancelReasonCode() { return cancelReasonCode; }
}

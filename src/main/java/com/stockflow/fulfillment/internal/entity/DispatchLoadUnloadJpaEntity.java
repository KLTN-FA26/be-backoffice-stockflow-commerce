package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.UnloadReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A parcel to take off a vehicle because its order was cancelled while the vehicle loaded (SCRUM-392) or because the
 * vehicle was called off under control (SCRUM-474 UNLOAD-DESIGN; table {@code fulfillment.dispatch_load_unload}): pending
 * until it is scanned off - or confirmed off without a scan by a warehouse manager ({@code overridden}).
 */
@Entity
@Table(name = "dispatch_load_unload", schema = "fulfillment")
public class DispatchLoadUnloadJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Column(name = "shipment_id", updatable = false) private UUID shipmentId;
    /** SCRUM-474: the transfer order of a parcel on a TRANSFER plan's vehicle (shipment_id null then). */
    @Column(name = "transfer_order_id", updatable = false) private UUID transferOrderId;
    @Column(name = "reason", nullable = false, updatable = false, length = 500) private String reason;
    /** Set by a controlled cancel only; null for a cancelled order's parcel. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", updatable = false, length = 20) private UnloadReasonCode reasonCode;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "unloaded_at") private Instant unloadedAt;
    @Column(name = "unloaded_by") private UUID unloadedBy;
    @Column(name = "overridden", nullable = false) private boolean overridden;
    @Column(name = "override_note", length = 500) private String overrideNote;

    protected DispatchLoadUnloadJpaEntity() {
    }

    /** @param transfer {@code key} is the parcel's transfer order, not a shipment */
    public DispatchLoadUnloadJpaEntity(UUID id, UUID loadId, UUID packageId, UUID key, boolean transfer, String reason,
                                       UnloadReasonCode reasonCode, Instant requestedAt) {
        super(id);
        this.loadId = loadId;
        this.packageId = packageId;
        this.shipmentId = transfer ? null : key;
        this.transferOrderId = transfer ? key : null;
        this.reason = reason;
        this.reasonCode = reasonCode;
        this.requestedAt = requestedAt;
    }

    public void apply(Instant unloadedAt, UUID unloadedBy, boolean overridden, String overrideNote) {
        this.unloadedAt = unloadedAt;
        this.unloadedBy = unloadedBy;
        this.overridden = overridden;
        this.overrideNote = overrideNote;
    }

    public UUID getLoadId() { return loadId; }
    public UUID getPackageId() { return packageId; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getTransferOrderId() { return transferOrderId; }

    /** What it was loaded with: its shipment, or its transfer order. */
    public UUID getKey() { return shipmentId != null ? shipmentId : transferOrderId; }
    public String getReason() { return reason; }
    public UnloadReasonCode getReasonCode() { return reasonCode; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getUnloadedAt() { return unloadedAt; }
    public UUID getUnloadedBy() { return unloadedBy; }
    public boolean isOverridden() { return overridden; }
    public String getOverrideNote() { return overrideNote; }
}

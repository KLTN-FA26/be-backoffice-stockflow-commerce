package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One call made to a carrier's webhook (table {@code fulfillment.carrier_event}; kltn-docs 09 §4.5 step 2, BR-27): kept
 * before anything is done with it, then decided once ({@link #decide}), and - for the error list - resolved once by a
 * warehouse manager ({@link #resolve}). What was received never changes ({@code fulfillment.carrier_event_guard}).
 */
@Entity
@Table(name = "carrier_event", schema = "fulfillment")
public class CarrierEventJpaEntity extends BaseEntity {

    @Column(name = "carrier_id", updatable = false) private UUID carrierId;
    @Column(name = "carrier_code", nullable = false, length = 64, updatable = false) private String carrierCode;
    @Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text") private String payload;
    @Column(name = "signature_valid", nullable = false, updatable = false) private boolean signatureValid;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", length = 24) private CarrierEventKind eventKind;
    @Column(name = "carrier_reference", length = 128) private String carrierReference;
    @Column(name = "carrier_reported_at") private Instant carrierReportedAt;
    @Column(name = "dedupe_key", length = 64) private String dedupeKey;
    @Column(name = "warehouse_id") private UUID warehouseId;
    @Column(name = "load_id") private UUID loadId;
    @Column(name = "shipment_id") private UUID shipmentId;
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20) private CarrierEventResult result;
    @Column(name = "result_note", length = 1000) private String resultNote;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "resolved_by") private UUID resolvedBy;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolution_note", length = 1000) private String resolutionNote;

    protected CarrierEventJpaEntity() {
    }

    /**
     * A call as received: {@code RECEIVED} when its credential held and it is still to be handled, otherwise decided at
     * once as {@code INVALID_SIGNATURE} with {@code note}.
     *
     * @param carrierId null when the URL named no carrier the system knows
     * @param payload   the body as received, the carrier's echo of a credential masked
     */
    public CarrierEventJpaEntity(UUID id, UUID carrierId, String carrierCode, Instant receivedAt, String payload,
                                 boolean signatureValid, String note) {
        super(id);
        this.carrierId = carrierId;
        this.carrierCode = carrierCode;
        this.receivedAt = receivedAt;
        this.payload = payload;
        this.signatureValid = signatureValid;
        if (signatureValid) {
            this.result = CarrierEventResult.RECEIVED;
        } else {
            this.result = CarrierEventResult.INVALID_SIGNATURE;
            this.resultNote = cut(note);
            this.decidedAt = receivedAt;
        }
    }

    /** What the call reported, once read; set before it is decided. */
    public void read(CarrierEventKind kind, String reference, Instant reportedAt) {
        this.eventKind = kind;
        this.carrierReference = reference == null || reference.length() <= 128 ? reference : reference.substring(0, 128);
        this.carrierReportedAt = reportedAt;
    }

    /** The load (and shipment) it was matched to, and the key that tells a repeat of it. */
    public void matched(UUID warehouseId, UUID loadId, UUID shipmentId, String dedupeKey) {
        this.warehouseId = warehouseId;
        this.loadId = loadId;
        this.shipmentId = shipmentId;
        this.dedupeKey = dedupeKey;
    }

    /** Not decided: it could not be handled now and may be when the carrier sends it again; why, meanwhile. */
    public void pending(String why) {
        this.resultNote = cut(why);
    }

    /** What came of it; once only (the database refuses a second decision). */
    public void decide(CarrierEventResult outcome, String note, Instant at) {
        if (outcome == CarrierEventResult.RECEIVED) {
            throw new IllegalArgumentException("RECEIVED is not a decision");
        }
        if (outcome == CarrierEventResult.INVALID_SIGNATURE) {
            throw new IllegalArgumentException("A call whose credential held is not INVALID_SIGNATURE");
        }
        this.result = outcome;
        this.resultNote = cut(note);
        this.decidedAt = at;
    }

    /** A warehouse manager dealt with an error-list item by hand (09 §4.5 step 8). */
    public void resolve(UUID by, Instant at, String note) {
        this.resolvedBy = by;
        this.resolvedAt = at;
        this.resolutionNote = cut(note);
    }

    private static String cut(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        return value.length() <= 1000 ? value : value.substring(0, 997) + "...";
    }

    public UUID getCarrierId() { return carrierId; }
    public String getCarrierCode() { return carrierCode; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getPayload() { return payload; }
    public boolean isSignatureValid() { return signatureValid; }
    public CarrierEventKind getEventKind() { return eventKind; }
    public String getCarrierReference() { return carrierReference; }
    public Instant getCarrierReportedAt() { return carrierReportedAt; }
    public String getDedupeKey() { return dedupeKey; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getLoadId() { return loadId; }
    public UUID getShipmentId() { return shipmentId; }
    public CarrierEventResult getResult() { return result; }
    public String getResultNote() { return resultNote; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
}

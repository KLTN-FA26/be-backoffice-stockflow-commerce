package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>A shipment</b>: the sealed parcels of one order that leave together (kltn-docs 09 §4.1 step 1, 08 step 9). One
 * order may have several (split shipments, G4-Q3; a mixed order's ready stock lines first, decision 2026-10-21), but a
 * parcel belongs to one order only (08 BR-04).
 *
 * <p>Born READY_TO_DISPATCH when packing completes (G4-5); the handover on a signed manifest (a {@link DispatchLoad}:
 * a load plan's vehicle, booked with one carrier) and the outcomes staff record (09 §4.3, BR-02) move it on through
 * {@link ShipmentStatus}. Every order ships as a full truckload and nothing is collected on delivery (09 BR-08,
 * 2026-11-05): it is handed over with its load and that load's carrier, and carries no amount.
 * {@code wholeOrderHandover}: the order knows the shipment only as the whole order - handed over before loads existed,
 * or packed before parcels recorded their contents - so its outcomes are the order's own.
 * Every step names who took it and when, and is
 * kept as a timeline row ({@link #pullRecorded}); a step to the status the shipment already has changes nothing, so a
 * retried call is harmless.</p>
 *
 * <p>Pure Java: no Spring, no JPA ({@code ArchitectureTest.domainDoesNotDependOnFrameworks}).</p>
 */
public final class Shipment {

    /** One step of the timeline, as {@code fulfillment.shipment_event} keeps it. */
    public record Step(ShipmentStatus status, Instant at, UUID by, String note) { }

    /** Everything a shipment row holds; how the aggregate is read back. */
    public record Snapshot(UUID id, String code, UUID orderId, UUID packId, UUID warehouseId, UUID requestId,
                           int packageCount, boolean partial, String partialNote, ShipmentStatus status,
                           Instant readyAt, UUID readyBy, Instant handedOverAt, UUID handedOverBy, Instant outcomeAt,
                           UUID outcomeBy, DeliveryFailureReason failureReason, String failureNote,
                           Instant cancelledAt, String cancelReason, UUID carrierId, UUID loadId,
                           boolean wholeOrderHandover) {

        /** Without carrier and load (shipments as G4-5 made them). */
        public Snapshot(UUID id, String code, UUID orderId, UUID packId, UUID warehouseId, UUID requestId,
                        int packageCount, boolean partial, String partialNote, ShipmentStatus status,
                        Instant readyAt, UUID readyBy, Instant handedOverAt, UUID handedOverBy, Instant outcomeAt,
                        UUID outcomeBy, DeliveryFailureReason failureReason, String failureNote,
                        Instant cancelledAt, String cancelReason) {
            this(id, code, orderId, packId, warehouseId, requestId, packageCount, partial, partialNote, status,
                    readyAt, readyBy, handedOverAt, handedOverBy, outcomeAt, outcomeBy, failureReason, failureNote,
                    cancelledAt, cancelReason, null, null, false);
        }
    }

    private final UUID id;
    private final String code;
    private final UUID orderId;
    private final UUID packId;
    private final UUID warehouseId;
    private final UUID requestId;
    private int packageCount;
    private final boolean partial;
    private final String partialNote;
    private ShipmentStatus status;
    private final Instant readyAt;
    private final UUID readyBy;
    private Instant handedOverAt;
    private UUID handedOverBy;
    private Instant outcomeAt;
    private UUID outcomeBy;
    private DeliveryFailureReason failureReason;
    private String failureNote;
    private Instant cancelledAt;
    private String cancelReason;
    private UUID carrierId;
    private UUID loadId;
    private boolean wholeOrderHandover;
    private final List<Step> recorded = new ArrayList<>();

    private Shipment(Snapshot s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.code = Objects.requireNonNull(s.code(), "code");
        this.orderId = Objects.requireNonNull(s.orderId(), "orderId");
        this.packId = s.packId();
        this.warehouseId = Objects.requireNonNull(s.warehouseId(), "warehouseId");
        this.requestId = s.requestId();
        this.packageCount = s.packageCount();
        this.partial = s.partial();
        this.partialNote = s.partialNote();
        this.status = Objects.requireNonNull(s.status(), "status");
        this.readyAt = Objects.requireNonNull(s.readyAt(), "readyAt");
        this.readyBy = s.readyBy();
        this.handedOverAt = s.handedOverAt();
        this.handedOverBy = s.handedOverBy();
        this.outcomeAt = s.outcomeAt();
        this.outcomeBy = s.outcomeBy();
        this.failureReason = s.failureReason();
        this.failureNote = s.failureNote();
        this.cancelledAt = s.cancelledAt();
        this.cancelReason = s.cancelReason();
        this.carrierId = s.carrierId();
        this.loadId = s.loadId();
        this.wholeOrderHandover = s.wholeOrderHandover();
    }

    /**
     * Packing completed (08 step 7-9): the sealed parcels wait at dispatch, READY_TO_DISPATCH (09 §4.1 step 1), for
     * a load plan.
     *
     * @param partialNote why only part of the order goes; null when it carries everything still to ship
     */
    public static Shipment ready(UUID id, String code, UUID orderId, UUID packId, UUID warehouseId, UUID requestId,
                                 int packageCount, String partialNote, UUID by, Instant at) {
        if (packageCount < 1) {
            throw new IllegalArgumentException("A shipment carries at least one package");
        }
        Objects.requireNonNull(by, "by");
        Shipment shipment = new Shipment(new Snapshot(id, code, orderId, packId, warehouseId, requestId, packageCount,
                partialNote != null, partialNote, ShipmentStatus.READY_TO_DISPATCH, at, by, null, null, null, null,
                null, null, null, null));
        shipment.recorded.add(new Step(ShipmentStatus.READY_TO_DISPATCH, at, by, partialNote));
        return shipment;
    }

    public static Shipment restore(Snapshot snapshot) {
        return new Shipment(snapshot);
    }

    /**
     * Handed to the carrier its vehicle was booked with, on the signed manifest of {@code loadId} (09 §4.2 step 5).
     * Again: nothing changes.
     *
     * @param wholeOrder the order took it as the whole order (it records no shipment of it)
     */
    public boolean handOver(UUID loadId, UUID carrierId, boolean wholeOrder, UUID by, Instant at) {
        Objects.requireNonNull(loadId, "the manifest it leaves on");
        Objects.requireNonNull(carrierId, "the carrier it is handed to");
        if (status == ShipmentStatus.HANDED_OVER && loadId.equals(this.loadId)) {
            return false;
        }
        recorder(by);
        if (!move(ShipmentStatus.HANDED_OVER)) {
            return false;
        }
        this.wholeOrderHandover = this.wholeOrderHandover || wholeOrder;
        this.loadId = loadId;
        this.carrierId = carrierId;
        handedOverAt = at;
        handedOverBy = by;
        recorded.add(new Step(status, at, by, null));
        return true;
    }

    /**
     * Some of its parcels stay behind when its load plan is confirmed (kltn-docs 09 §4.2 step 3 and edge "kiện không xếp
     * được ... để lại khu DISPATCH cho chuyến sau, giao lệch chuyến", SCRUM-392): they become a new shipment waiting for
     * dispatch, born now, and this one keeps the rest. Only a shipment waiting for dispatch is split; the caller moves
     * the parcels.
     *
     * @param parcels how many parcels go to the new shipment (at least one)
     * @param staying how many stay with this one (at least one)
     * @return the new shipment, READY_TO_DISPATCH, partial with {@code note}
     */
    public Shipment splitOff(UUID newId, String newCode, UUID newRequestId, int parcels, int staying, String note,
                             UUID by, Instant at) {
        if (status != ShipmentStatus.READY_TO_DISPATCH) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT,
                    "Shipment %s is %s; only a shipment waiting for dispatch is split".formatted(code, status));
        }
        if (parcels < 1 || staying < 1) {
            throw new IllegalArgumentException("A split moves at least one parcel and leaves at least one");
        }
        packageCount = staying;
        return ready(newId, newCode, orderId, packId, warehouseId, newRequestId, parcels, note, by, at);
    }

    /** The carrier reported it delivered, on the first try or a retry (09 §4.3 step 1-2); recorded by a person. */
    public boolean recordDelivered(UUID by, Instant at) {
        return delivered(recorder(by), at);
    }

    /** The carrier could not deliver it (09 §4.3 step 2); OTHER needs a note. Recorded by a person. */
    public boolean recordFailed(DeliveryFailureReason reason, String note, UUID by, Instant at) {
        return failed(reason, note, recorder(by), at);
    }

    /** The parcels came back to the warehouse (09 §4.3 step 3, BR-06); recorded by a person. */
    public boolean recordReturned(UUID by, Instant at) {
        return returned(recorder(by), at);
    }

    /**
     * The carrier's webhook reported it delivered (09 §4.5 step 7): nobody recorded it, and {@code at} is the time the
     * carrier gives - the delivery's own time (E25).
     */
    public boolean carrierDelivered(Instant at) {
        return delivered(null, at);
    }

    /** The carrier's webhook reported it not delivered, its reason mapped (E22): recorded by nobody. */
    public boolean carrierFailed(DeliveryFailureReason reason, String note, Instant at) {
        return failed(reason, note, null, at);
    }

    /** The carrier's webhook reported the goods back at the warehouse: recorded by nobody. */
    public boolean carrierReturned(Instant at) {
        return returned(null, at);
    }

    /**
     * The warehouse manager corrects an outcome the carrier reported wrongly (09 §4.3 step 5, E17): DELIVERED →
     * DELIVERY_FAILED (with the reason it failed) or DELIVERY_FAILED → DELIVERED, recorded by them, the reason being
     * the correction's. Nothing else is a correction.
     *
     * @return whether it changed anything (false: the shipment already has that outcome)
     * @throws BusinessException {@code SHIPMENT_STATE_CONFLICT} for a shipment with neither outcome
     */
    public boolean correctOutcome(ShipmentStatus target, DeliveryFailureReason reason, String note, UUID by,
                                  Instant at) {
        recorder(by);
        if (status == target) {
            return false;
        }
        if (target == ShipmentStatus.DELIVERED && status == ShipmentStatus.DELIVERY_FAILED) {
            return delivered(by, at);
        }
        if (target != ShipmentStatus.DELIVERY_FAILED || status != ShipmentStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, ("Shipment %s is %s: only a delivery or a "
                    + "failed delivery is corrected").formatted(code, status));
        }
        Objects.requireNonNull(reason, "reason");
        if (reason == DeliveryFailureReason.OTHER && (note == null || note.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failure for another reason says what it was");
        }
        status = ShipmentStatus.DELIVERY_FAILED;
        outcomeAt = at;
        outcomeBy = by;
        failureReason = reason;
        failureNote = note;
        recorded.add(new Step(status, at, by, note));
        return true;
    }

    private boolean delivered(UUID by, Instant at) {
        if (!move(ShipmentStatus.DELIVERED)) {
            return false;
        }
        outcomeAt = at;
        outcomeBy = by;
        recorded.add(new Step(status, at, by, null));
        return true;
    }

    private boolean failed(DeliveryFailureReason reason, String note, UUID by, Instant at) {
        Objects.requireNonNull(reason, "reason");
        if (reason == DeliveryFailureReason.OTHER && (note == null || note.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failure for another reason says what it was");
        }
        if (!move(ShipmentStatus.DELIVERY_FAILED)) {
            return false;
        }
        outcomeAt = at;
        outcomeBy = by;
        failureReason = reason;
        failureNote = note;
        recorded.add(new Step(status, at, by, note));
        return true;
    }

    private boolean returned(UUID by, Instant at) {
        if (!move(ShipmentStatus.RETURNED)) {
            return false;
        }
        outcomeAt = at;
        outcomeBy = by;
        recorded.add(new Step(status, at, by, null));
        return true;
    }

    /** A person recording an outcome is always named (09 BR-02); only the carrier's webhook records as nobody. */
    private static UUID recorder(UUID by) {
        return Objects.requireNonNull(by, "who recorded it (09 BR-02)");
    }

    /** Before the handover only: the order was cancelled or the parcels are taken apart again (08 BR-05, BR-06). */
    public boolean cancel(String reason, UUID by, Instant at) {
        if (status == ShipmentStatus.CANCELLED) {
            return false;
        }
        if (!status.canTransitionTo(ShipmentStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT,
                    "Shipment %s is %s; only one waiting for dispatch is cancelled".formatted(code, status));
        }
        status = ShipmentStatus.CANCELLED;
        cancelledAt = at;
        cancelReason = reason;
        recorded.add(new Step(status, at, by, reason));
        return true;
    }

    private boolean move(ShipmentStatus target) {
        if (status == target) {
            return false;
        }
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT,
                    "Shipment %s is %s; it cannot become %s".formatted(code, status, target));
        }
        status = target;
        return true;
    }

    /** The timeline steps taken since it was read, oldest first; cleared by the call. */
    public List<Step> pullRecorded() {
        List<Step> copy = List.copyOf(recorded);
        recorded.clear();
        return copy;
    }

    public Snapshot snapshot() {
        return new Snapshot(id, code, orderId, packId, warehouseId, requestId, packageCount, partial, partialNote,
                status, readyAt, readyBy, handedOverAt, handedOverBy, outcomeAt, outcomeBy, failureReason, failureNote,
                cancelledAt, cancelReason, carrierId, loadId, wholeOrderHandover);
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public UUID orderId() { return orderId; }
    public ShipmentStatus status() { return status; }
    public int packageCount() { return packageCount; }
    public boolean partial() { return partial; }
    public UUID warehouseId() { return warehouseId; }
    public Instant readyAt() { return readyAt; }
    public UUID carrierId() { return carrierId; }
    public UUID loadId() { return loadId; }
    public boolean wholeOrderHandover() { return wholeOrderHandover; }
    public DeliveryFailureReason failureReason() { return failureReason; }
}

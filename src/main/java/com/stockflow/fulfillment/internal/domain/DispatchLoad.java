package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * <b>A load</b>: one handover to one carrier's vehicle, and the manifest both sides sign for it (kltn-docs 09 §4.1
 * step 3, §4.2 step 5, BR-15). Parcel branch (G4-2a): opened LOADING when the driver arrives, with the shipments the
 * dispatcher puts on its manifest - shipments of its warehouse, waiting for dispatch, of its carrier or of none yet.
 * Each parcel is scanned onto the vehicle and checked against that list ("đối chiếu danh sách"); a shipment leaves only
 * with all its parcels (BR-03 at the shipment's level). Both sides sign: DISPATCHED, and the shipments are handed over.
 * The FTL branch (G4-1/G4-2b, SCRUM-392) creates it PLANNED from a confirmed load plan's vehicle, with the shipments
 * whose parcels are placed on that vehicle - one shipment may span several vehicles of the plan (LP-3). The dispatcher
 * books the vehicle (VEHICLE_BOOKED; a booking is cancelled back to PLANNED until loading starts), starts loading when
 * it arrives (LOADING), scans only the parcels placed on it (others are refused naming their vehicle) and both sides
 * sign. A parcel of an order cancelled while it is on board is to be unloaded first ({@link #withdrawShipment}): the
 * vehicle is not handed over while one is pending ({@code LOAD_UNLOAD_PENDING}).
 *
 * <p>Every step on an open load is idempotent: a shipment already on it, a parcel already loaded, a parcel not loaded,
 * a load already dispatched - nothing changes and nothing is refused. What another load holds is refused, naming that
 * load (09 edge "quét nhầm kiện của xe / chuyến khác → chặn, báo kiện thuộc xe / kế hoạch nào").</p>
 *
 * <p>Pure Java: no Spring, no JPA. What it must know of shipments and parcels it does not own is passed in
 * ({@link ShipmentFacts}, {@link ParcelFacts}); the service reads those under the load's lock.</p>
 */
public final class DispatchLoad {

    /** What the load needs to know of a shipment to take it on its manifest. */
    public record ShipmentFacts(UUID shipmentId, String code, UUID warehouseId, DeliveryMode mode, ShipmentStatus status,
                                UUID carrierId, String otherLoadCode) { }

    /**
     * What the load needs to know of a scanned parcel.
     *
     * @param shipment      its shipment; null for a parcel in none (still at the packing table)
     * @param otherLoadCode the open load it is loaded on, when not this one
     * @param placement     where the plan of a full-truckload load placed it; null for a parcel load
     */
    public record ParcelFacts(UUID packageId, String labelCode, PackageStatus status, ShipmentFacts shipment,
                              String otherLoadCode, Placement placement) {

        public ParcelFacts(UUID packageId, String labelCode, PackageStatus status, ShipmentFacts shipment,
                           String otherLoadCode) {
            this(packageId, labelCode, status, shipment, otherLoadCode, null);
        }
    }

    /**
     * Where the load plan placed a scanned parcel (09 edge "quét nhầm kiện của xe / chuyến khác → chặn, báo kiện thuộc xe
     * / kế hoạch nào").
     *
     * @param here       placed on this load's vehicle
     * @param planCode   the plan it is placed in; null when no plan of this vehicle's holds it
     * @param vehicleSeq the vehicle it is placed on; null when not placed
     * @param loadCode   that vehicle's live load; null when not placed or none is live
     */
    public record Placement(boolean here, String planCode, Integer vehicleSeq, String loadCode) { }

    /**
     * A parcel to take off the vehicle again: its order was cancelled while it was on board (09 edge "đã xếp lên xe →
     * dỡ kiện"). Pending while {@code unloadedAt} is null.
     */
    public record Unload(UUID packageId, UUID shipmentId, String reason, Instant requestedAt, Instant unloadedAt,
                         UUID unloadedBy, UnloadReasonCode reasonCode, boolean overridden, String overrideNote) {

        /** A parcel of a cancelled order (no reason code, scanned off normally until it says otherwise). */
        public Unload(UUID packageId, UUID shipmentId, String reason, Instant requestedAt, Instant unloadedAt,
                      UUID unloadedBy) {
            this(packageId, shipmentId, reason, requestedAt, unloadedAt, unloadedBy, null, false, null);
        }

        public boolean pending() {
            return unloadedAt == null;
        }

        Unload scannedOff(Instant at, UUID by) {
            return new Unload(packageId, shipmentId, reason, requestedAt, at, by, reasonCode, false, null);
        }

        Unload confirmedWithoutScan(Instant at, UUID by, String note) {
            return new Unload(packageId, shipmentId, reason, requestedAt, at, by, reasonCode, true, note);
        }
    }

    /** A shipment on the manifest; {@code active} false once taken off before the handover. */
    public record Member(UUID shipmentId, boolean active, Instant addedAt, UUID addedBy, Instant removedAt,
                         UUID removedBy, BigDecimal labelCodAmount, BigDecimal codAmount) { }

    /** A parcel on the vehicle; {@code scanId} is the last scan that put it there. */
    public record Loaded(UUID packageId, UUID shipmentId, UUID scanId, Instant loadedAt, UUID loadedBy, boolean active,
                         Instant removedAt, UUID removedBy) { }

    /** Both sides sign the manifest (§4.1 step 3). */
    public record Handover(String carrierRepresentative, String vehiclePlate, String driverPhone, String signatureRef,
                           boolean signed, String note) { }

    public record Snapshot(UUID id, String code, UUID requestId, UUID warehouseId, DeliveryMode mode, UUID carrierId,
                           UUID loadPlanVehicleId, String vehiclePlate, String driverName, String driverPhone,
                           LoadStatus status, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                           UUID dispatchedBy, String carrierRepresentative, String signatureRef, String handoverNote,
                           BigDecimal codTotal, Instant cancelledAt, UUID cancelledBy, String cancelReason,
                           long version, List<Member> members, List<Loaded> loaded, Instant bookedAt, UUID bookedBy,
                           Instant bookingCancelledAt, UUID bookingCancelledBy, String bookingCancelReason,
                           List<Unload> unloads, UnloadReasonCode cancelReasonCode) {

        /** Without the code of a controlled cancel (every load but one called off after loading started). */
        public Snapshot(UUID id, String code, UUID requestId, UUID warehouseId, DeliveryMode mode, UUID carrierId,
                        UUID loadPlanVehicleId, String vehiclePlate, String driverName, String driverPhone,
                        LoadStatus status, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                        UUID dispatchedBy, String carrierRepresentative, String signatureRef, String handoverNote,
                        BigDecimal codTotal, Instant cancelledAt, UUID cancelledBy, String cancelReason,
                        long version, List<Member> members, List<Loaded> loaded, Instant bookedAt, UUID bookedBy,
                        Instant bookingCancelledAt, UUID bookingCancelledBy, String bookingCancelReason,
                        List<Unload> unloads) {
            this(id, code, requestId, warehouseId, mode, carrierId, loadPlanVehicleId, vehiclePlate, driverName,
                    driverPhone, status, loadingStartedAt, loadingStartedBy, dispatchedAt, dispatchedBy,
                    carrierRepresentative, signatureRef, handoverNote, codTotal, cancelledAt, cancelledBy,
                    cancelReason, version, members, loaded, bookedAt, bookedBy, bookingCancelledAt,
                    bookingCancelledBy, bookingCancelReason, unloads, null);
        }

        /** Without booking and unloads (parcel loads, and callers written before SCRUM-392). */
        public Snapshot(UUID id, String code, UUID requestId, UUID warehouseId, DeliveryMode mode, UUID carrierId,
                        UUID loadPlanVehicleId, String vehiclePlate, String driverName, String driverPhone,
                        LoadStatus status, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                        UUID dispatchedBy, String carrierRepresentative, String signatureRef, String handoverNote,
                        BigDecimal codTotal, Instant cancelledAt, UUID cancelledBy, String cancelReason,
                        long version, List<Member> members, List<Loaded> loaded) {
            this(id, code, requestId, warehouseId, mode, carrierId, loadPlanVehicleId, vehiclePlate, driverName,
                    driverPhone, status, loadingStartedAt, loadingStartedBy, dispatchedAt, dispatchedBy,
                    carrierRepresentative, signatureRef, handoverNote, codTotal, cancelledAt, cancelledBy, cancelReason,
                    version, members, loaded, null, null, null, null, null, List.of());
        }
    }

    private final UUID id;
    private final String code;
    private final UUID requestId;
    private final UUID warehouseId;
    private final DeliveryMode mode;
    private UUID carrierId;
    private final UUID loadPlanVehicleId;
    private String vehiclePlate;
    private String driverName;
    private String driverPhone;
    private LoadStatus status;
    private Instant bookedAt;
    private UUID bookedBy;
    private Instant bookingCancelledAt;
    private UUID bookingCancelledBy;
    private String bookingCancelReason;
    private Instant loadingStartedAt;
    private UUID loadingStartedBy;
    private Instant dispatchedAt;
    private UUID dispatchedBy;
    private String carrierRepresentative;
    private String signatureRef;
    private String handoverNote;
    private BigDecimal codTotal;
    private Instant cancelledAt;
    private UUID cancelledBy;
    private String cancelReason;
    private UnloadReasonCode cancelReasonCode;
    private final long version;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final Map<UUID, Loaded> loaded = new LinkedHashMap<>();
    private final Map<UUID, Unload> unloads = new LinkedHashMap<>();

    private DispatchLoad(Snapshot s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.code = Objects.requireNonNull(s.code(), "code");
        this.requestId = Objects.requireNonNull(s.requestId(), "requestId");
        this.warehouseId = Objects.requireNonNull(s.warehouseId(), "warehouseId");
        this.mode = Objects.requireNonNull(s.mode(), "mode");
        this.carrierId = s.carrierId();
        this.loadPlanVehicleId = s.loadPlanVehicleId();
        this.vehiclePlate = s.vehiclePlate();
        this.driverName = s.driverName();
        this.driverPhone = s.driverPhone();
        this.status = Objects.requireNonNull(s.status(), "status");
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
        this.codTotal = s.codTotal() == null ? BigDecimal.ZERO : s.codTotal();
        this.cancelledAt = s.cancelledAt();
        this.cancelledBy = s.cancelledBy();
        this.cancelReason = s.cancelReason();
        this.cancelReasonCode = s.cancelReasonCode();
        this.version = s.version();
        if (s.members() != null) {
            s.members().forEach(m -> members.put(m.shipmentId(), m));
        }
        if (s.loaded() != null) {
            s.loaded().forEach(l -> loaded.put(l.packageId(), l));
        }
        if (s.unloads() != null) {
            s.unloads().forEach(u -> unloads.put(u.packageId(), u));
        }
    }

    /**
     * The carrier's driver arrived for parcels (09 §4.1 step 3): a load of one carrier at one warehouse, LOADING at
     * once. The vehicle and driver may be known now or at the signature.
     */
    public static DispatchLoad openParcel(UUID id, String code, UUID requestId, UUID warehouseId, UUID carrierId,
                                          String vehiclePlate, String driverName, String driverPhone, UUID by,
                                          Instant at) {
        Objects.requireNonNull(carrierId, "a parcel load names its carrier (09 §3)");
        Objects.requireNonNull(by, "by");
        return new DispatchLoad(new Snapshot(id, code, requestId, warehouseId, DeliveryMode.PARCEL, carrierId, null,
                trimmed(vehiclePlate), trimmed(driverName), trimmed(driverPhone), LoadStatus.LOADING, at, by, null,
                null, null, null, null, BigDecimal.ZERO, null, null, null, 0, List.of(), List.of()));
    }

    /**
     * A vehicle of a confirmed load plan (09 §4.2 step 4-5, SCRUM-287 PR D): a full-truckload load, PLANNED - no carrier
     * or vehicle yet. The plan's confirmation puts its shipments on the manifest ({@link #addShipment}); booking,
     * loading and the signature follow (SCRUM-392).
     */
    public static DispatchLoad planFullTruckload(UUID id, String code, UUID requestId, UUID warehouseId,
                                                 UUID loadPlanVehicleId) {
        Objects.requireNonNull(loadPlanVehicleId, "a full-truckload load is a vehicle of a load plan");
        return new DispatchLoad(new Snapshot(id, code, requestId, warehouseId, DeliveryMode.FULL_TRUCKLOAD, null,
                loadPlanVehicleId, null, null, null, LoadStatus.PLANNED, null, null, null, null, null, null, null,
                BigDecimal.ZERO, null, null, null, 0, List.of(), List.of()));
    }

    public static DispatchLoad restore(Snapshot snapshot) {
        return new DispatchLoad(snapshot);
    }

    // ---- the manifest's list -------------------------------------------------------------------------------------

    /**
     * Puts a shipment on the manifest (09 §4.1 step 2-3): waiting for dispatch, of this warehouse and delivery mode,
     * of this carrier or of none chosen yet, on no other open load. Already on it: nothing changes.
     *
     * @param labelCod what its label says the carrier collects (BR-04), shown on the manifest until the handover fixes it
     * @return whether it changed the load
     */
    public boolean addShipment(ShipmentFacts shipment, BigDecimal labelCod, UUID by, Instant at) {
        requireOpen("put shipments on it");
        Member member = members.get(shipment.shipmentId());
        if (member != null && member.active()) {
            return false;
        }
        if (shipment.status() != ShipmentStatus.READY_TO_DISPATCH) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT,
                    "Shipment %s is %s; only one waiting for dispatch goes on a manifest".formatted(shipment.code(),
                            shipment.status()));
        }
        if (!warehouseId.equals(shipment.warehouseId())) {
            throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOR_LOAD,
                    "Shipment %s leaves from another warehouse than load %s".formatted(shipment.code(), code));
        }
        if (shipment.mode() != mode) {
            throw new BusinessException(mode == DeliveryMode.PARCEL ? ErrorCode.PACKAGE_REQUIRES_LOAD_PLAN
                    : ErrorCode.SHIPMENT_NOT_FOR_LOAD,
                    "Shipment %s travels as %s; load %s is %s".formatted(shipment.code(), shipment.mode(), code, mode));
        }
        if (carrierId != null && shipment.carrierId() != null && !shipment.carrierId().equals(carrierId)) {
            throw new BusinessException(ErrorCode.SHIPMENT_OTHER_CARRIER,
                    "Shipment %s goes with another carrier than load %s".formatted(shipment.code(), code));
        }
        // A full truckload's shipment may span the vehicles of its plan (LP-3): the caller leaves those out of
        // otherLoadCode, so what is left is a load of another plan or a parcel load.
        if (shipment.otherLoadCode() != null) {
            throw new BusinessException(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD,
                    "Shipment %s is on load %s; take it off there first".formatted(shipment.code(),
                            shipment.otherLoadCode()));
        }
        members.put(shipment.shipmentId(), new Member(shipment.shipmentId(), true, at, Objects.requireNonNull(by),
                null, null, labelCod, null));
        return true;
    }

    /**
     * Takes a shipment off the manifest before the handover, with its parcels (they stay at the dispatch area for
     * another load). Not on it: nothing changes.
     */
    public boolean removeShipment(UUID shipmentId, UUID by, Instant at) {
        requireOpen("take shipments off it");
        Member member = members.get(shipmentId);
        if (member == null || !member.active()) {
            return false;
        }
        members.put(shipmentId, new Member(shipmentId, false, member.addedAt(), member.addedBy(), at, by,
                member.labelCodAmount(), null));
        for (Loaded parcel : List.copyOf(loaded.values())) {
            if (parcel.active() && parcel.shipmentId().equals(shipmentId)) {
                loaded.put(parcel.packageId(), unloaded(parcel, by, at));
            }
        }
        return true;
    }

    /**
     * The shipment's order was cancelled before the handover (08 BR-05, 09 edge "đơn bị huỷ khi kiện đã nằm trong kế
     * hoạch chuyến xe"): off the manifest. On a full truckload being loaded, its parcels already scanned on board are
     * to be unloaded ({@link #confirmUnload}) before the vehicle may leave; elsewhere they simply come off. Not on it, or
     * the load is no longer open: nothing changes.
     *
     * @return whether it changed the load
     */
    public boolean withdrawShipment(UUID shipmentId, String reason, Instant at) {
        Member member = members.get(shipmentId);
        if (!status.isOpen() || member == null || !member.active()) {
            return false;
        }
        members.put(shipmentId, new Member(shipmentId, false, member.addedAt(), member.addedBy(), at, null,
                member.labelCodAmount(), null));
        boolean onBoard = mode == DeliveryMode.FULL_TRUCKLOAD && status == LoadStatus.LOADING;
        for (Loaded parcel : List.copyOf(loaded.values())) {
            if (parcel.active() && parcel.shipmentId().equals(shipmentId)) {
                loaded.put(parcel.packageId(), unloaded(parcel, null, at));
                if (onBoard && !unloads.containsKey(parcel.packageId())) {
                    unloads.put(parcel.packageId(), new Unload(parcel.packageId(), shipmentId,
                            Objects.requireNonNullElse(trimmed(reason), "The order was cancelled"), at, null, null));
                }
            }
        }
        return true;
    }

    /**
     * A parcel to unload was taken off the vehicle (scanned off). Already unloaded: nothing changes.
     *
     * @throws BusinessException {@code PACKAGE_NOT_IN_LOAD} when the parcel is not to be unloaded from this vehicle
     */
    public boolean confirmUnload(UUID packageId, String labelCode, UUID by, Instant at) {
        Unload unload = unloads.get(packageId);
        if (unload == null) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_IN_LOAD,
                    "Package %s is not to be unloaded from load %s".formatted(labelCode, code));
        }
        if (!unload.pending()) {
            return false;
        }
        unloads.put(packageId, unload.scannedOff(at, Objects.requireNonNull(by)));
        return true;
    }

    /**
     * A parcel to unload is confirmed off the vehicle <b>without a scan</b>, because its label is damaged (UNLOAD-DESIGN
     * point 5). The caller has checked the permission; who, when and why (the note, optional) stay on the record, flagged
     * {@code overridden}. Already unloaded: nothing changes.
     *
     * @throws BusinessException {@code PACKAGE_NOT_IN_LOAD} when the parcel is not to be unloaded from this vehicle
     */
    public boolean confirmUnloadWithoutScan(UUID packageId, String labelCode, String note, UUID by, Instant at) {
        Unload unload = unloads.get(packageId);
        if (unload == null) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_IN_LOAD,
                    "Package %s is not to be unloaded from load %s".formatted(labelCode, code));
        }
        if (!unload.pending()) {
            return false;
        }
        unloads.put(packageId, unload.confirmedWithoutScan(at, Objects.requireNonNull(by), trimmed(note)));
        return true;
    }

    // ---- the booking (full truckload, 09 §4.2 step 5) --------------------------------------------------------------

    /**
     * The vehicle was booked with a carrier outside the system and is recorded here (09 §4.2 step 5 assumption: plate
     * and driver may be known now or at the signature): PLANNED → VEHICLE_BOOKED. Booked again before loading: the
     * booking's details are replaced; the same details again change nothing.
     *
     * @return whether it changed the load
     */
    public boolean book(UUID carrier, String plate, String driver, String phone, UUID by, Instant at) {
        requireFullTruckload("be booked");
        Objects.requireNonNull(carrier, "the carrier the vehicle is booked with");
        if (status != LoadStatus.PLANNED && status != LoadStatus.VEHICLE_BOOKED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION,
                    "Load %s is %s; a vehicle is booked before loading starts".formatted(code, status));
        }
        if (status == LoadStatus.VEHICLE_BOOKED && carrier.equals(carrierId)
                && Objects.equals(trimmed(plate), vehiclePlate) && Objects.equals(trimmed(driver), driverName)
                && Objects.equals(trimmed(phone), driverPhone)) {
            return false;
        }
        carrierId = carrier;
        vehiclePlate = trimmed(plate);
        driverName = trimmed(driver);
        driverPhone = trimmed(phone);
        bookedAt = at;
        bookedBy = Objects.requireNonNull(by);
        status = LoadStatus.VEHICLE_BOOKED;
        return true;
    }

    /**
     * The booking fell through before loading started (the carrier cancelled, another vehicle is booked instead):
     * VEHICLE_BOOKED → PLANNED, the carrier and vehicle cleared, the reason kept. PLANNED already: nothing changes.
     *
     * @throws BusinessException {@code INVALID_LOAD_TRANSITION} once loading started; {@code VALIDATION_FAILED} without a
     *         reason
     */
    public boolean cancelBooking(String reason, UUID by, Instant at) {
        requireFullTruckload("have its booking cancelled");
        if (status == LoadStatus.PLANNED) {
            return false;
        }
        if (status != LoadStatus.VEHICLE_BOOKED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is %s; a booking is cancelled "
                    + "only before loading starts").formatted(code, status));
        }
        String why = trimmed(reason);
        if (why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cancelling a booking says why");
        }
        carrierId = null;
        vehiclePlate = null;
        driverName = null;
        driverPhone = null;
        bookedAt = null;
        bookedBy = null;
        bookingCancelledAt = at;
        bookingCancelledBy = by;
        bookingCancelReason = why;
        status = LoadStatus.PLANNED;
        return true;
    }

    /** The booked vehicle arrived and loading starts (BR-14: its plan is locked from now on). LOADING already: no-op. */
    public boolean startLoading(UUID by, Instant at) {
        requireFullTruckload("start loading this way");
        if (status == LoadStatus.LOADING) {
            return false;
        }
        if (status != LoadStatus.VEHICLE_BOOKED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, (status == LoadStatus.PLANNED
                    ? "Load %s has no vehicle booked yet: book it first" : "Load %s is %s and cannot start loading")
                    .formatted(code, status));
        }
        status = LoadStatus.LOADING;
        loadingStartedAt = at;
        loadingStartedBy = Objects.requireNonNull(by);
        return true;
    }

    // ---- the vehicle -----------------------------------------------------------------------------------------------

    /**
     * One parcel scanned onto the vehicle (09 §4.1 step 3), checked against the manifest. Already loaded here:
     * nothing changes (a second scan of the same label, with another scan id). Refused, writing nothing: a parcel not
     * sealed or whose shipment no longer waits for dispatch ({@code PACKAGE_NOT_READY}); one on another open load
     * ({@code PACKAGE_ALREADY_LOADED}, naming it); of another warehouse or of a shipment not on this manifest
     * ({@code PACKAGE_NOT_IN_LOAD}); of another carrier ({@code PACKAGE_OTHER_CARRIER}); a full truckload's parcel
     * on a parcel load ({@code PACKAGE_REQUIRES_LOAD_PLAN}).
     *
     * @return whether it changed the load
     */
    public boolean scan(UUID scanId, ParcelFacts parcel, UUID by, Instant at) {
        Objects.requireNonNull(scanId, "scanId");
        requireLoading("scan parcels onto it");
        Loaded here = loaded.get(parcel.packageId());
        if (here != null && here.active()) {
            return false;
        }
        ShipmentFacts shipment = parcel.shipment();
        if (parcel.status() != PackageStatus.SEALED || shipment == null) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_READY, "Package %s is %s%s; it is not ready to hand over"
                    .formatted(parcel.labelCode(), parcel.status(), shipment == null ? " and in no shipment" : ""));
        }
        if (shipment.status() != ShipmentStatus.READY_TO_DISPATCH) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_READY, "Package %s belongs to shipment %s, which is %s"
                    .formatted(parcel.labelCode(), shipment.code(), shipment.status()));
        }
        if (parcel.otherLoadCode() != null) {
            throw new BusinessException(ErrorCode.PACKAGE_ALREADY_LOADED, "Package %s is already on load %s"
                    .formatted(parcel.labelCode(), parcel.otherLoadCode()));
        }
        if (!warehouseId.equals(shipment.warehouseId())) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_IN_LOAD, "Package %s leaves from another warehouse"
                    .formatted(parcel.labelCode()));
        }
        if (shipment.mode() != mode) {
            throw new BusinessException(mode == DeliveryMode.PARCEL ? ErrorCode.PACKAGE_REQUIRES_LOAD_PLAN
                    : ErrorCode.PACKAGE_NOT_IN_LOAD, "Package %s travels as %s; load %s is %s"
                    .formatted(parcel.labelCode(), shipment.mode(), code, mode));
        }
        if (mode == DeliveryMode.FULL_TRUCKLOAD) {
            requirePlacedHere(parcel);
        } else if (shipment.carrierId() != null && !shipment.carrierId().equals(carrierId)) {
            throw new BusinessException(ErrorCode.PACKAGE_OTHER_CARRIER, "Package %s goes with another carrier"
                    .formatted(parcel.labelCode()));
        }
        Member member = members.get(shipment.shipmentId());
        if (member == null || !member.active()) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_IN_LOAD, (shipment.otherLoadCode() != null
                    ? "Package %s belongs to shipment %s, which is on load %s".formatted(parcel.labelCode(),
                    shipment.code(), shipment.otherLoadCode())
                    : "Package %s belongs to shipment %s, which is not on this manifest: put the shipment on it first"
                    .formatted(parcel.labelCode(), shipment.code())));
        }
        loaded.put(parcel.packageId(), new Loaded(parcel.packageId(), shipment.shipmentId(), scanId, at,
                Objects.requireNonNull(by), true, null, null));
        return true;
    }

    /**
     * 09 §4.2 step 5 ("hệ thống chặn kiện không thuộc xe này"): a full truckload takes only the parcels its plan placed on
     * it; another is refused naming the vehicle and load it belongs on ({@code PACKAGE_NOT_IN_LOAD}).
     */
    private void requirePlacedHere(ParcelFacts parcel) {
        Placement placed = parcel.placement();
        if (placed != null && placed.here()) {
            return;
        }
        List<com.stockflow.common.api.FieldError> where = new ArrayList<>();
        String message;
        if (placed == null || placed.vehicleSeq() == null) {
            message = placed == null || placed.planCode() == null
                    ? "Package %s is in no load plan; it does not go on load %s".formatted(parcel.labelCode(), code)
                    : "Package %s is in load plan %s but has no place on this vehicle (load %s)".formatted(
                    parcel.labelCode(), placed.planCode(), code);
        } else {
            message = "Package %s is placed on vehicle %d%s of plan %s, not on load %s".formatted(parcel.labelCode(),
                    placed.vehicleSeq(), placed.loadCode() == null ? "" : " (load " + placed.loadCode() + ")",
                    placed.planCode(), code);
            where.add(com.stockflow.common.api.FieldError.of("vehicleSeq", String.valueOf(placed.vehicleSeq())));
            if (placed.loadCode() != null) {
                where.add(com.stockflow.common.api.FieldError.of("loadCode", placed.loadCode()));
            }
        }
        throw new BusinessException(ErrorCode.PACKAGE_NOT_IN_LOAD, message, where);
    }

    /** Takes a scanned parcel off the vehicle again before the handover. Not loaded here: nothing changes. */
    public boolean unscan(UUID packageId, UUID by, Instant at) {
        requireLoading("take parcels off it");
        Loaded parcel = loaded.get(packageId);
        if (parcel == null || !parcel.active()) {
            return false;
        }
        loaded.put(packageId, unloaded(parcel, by, at));
        return true;
    }

    /**
     * Both sides sign the manifest (09 §4.1 step 3): DISPATCHED. Every shipment on it leaves with all its parcels
     * ({@code parcelsByShipment}: the sealed parcels each one has) - one with a parcel not scanned is refused,
     * naming the parcels ({@code LOAD_NOT_FULLY_LOADED}); take the shipment off to let the rest go. The driver's name
     * and the vehicle plate are on the manifest. Already dispatched: nothing changes.
     *
     * @return the shipments to hand over, in the order they were put on; empty when it was dispatched before
     */
    public List<UUID> handOver(Handover handover, Map<UUID, Set<UUID>> parcelsByShipment,
                               Map<UUID, String> labels, UUID by, Instant at) {
        if (status == LoadStatus.DISPATCHED) {
            return List.of();
        }
        requireLoading("be handed over");
        List<Unload> pending = pendingUnloads();
        if (!pending.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_UNLOAD_PENDING, ("Load %s still carries %d parcel(s) of a "
                    + "cancelled order: unload them first").formatted(code, pending.size()),
                    pending.stream().map(u -> com.stockflow.common.api.FieldError.of("packageId",
                            u.packageId().toString())).toList());
        }
        if (!handover.signed()) {
            throw new BusinessException(ErrorCode.HANDOVER_NOT_SIGNED,
                    "Both sides sign the manifest of load %s before it leaves".formatted(code));
        }
        String representative = trimmed(handover.carrierRepresentative());
        String plate = trimmed(handover.vehiclePlate()) != null ? trimmed(handover.vehiclePlate()) : vehiclePlate;
        if (representative == null || plate == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The manifest names who signed for the carrier and the vehicle plate");
        }
        List<UUID> leaving = members.values().stream().filter(Member::active).map(Member::shipmentId).toList();
        if (leaving.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_EMPTY, "Load %s has no shipment on its manifest".formatted(code));
        }
        List<String> missing = new ArrayList<>();
        for (UUID shipmentId : leaving) {
            for (UUID packageId : parcelsByShipment.getOrDefault(shipmentId, Set.of())) {
                Loaded parcel = loaded.get(packageId);
                if (parcel == null || !parcel.active()) {
                    missing.add(labels.getOrDefault(packageId, packageId.toString()));
                }
            }
            if (parcelsByShipment.getOrDefault(shipmentId, Set.of()).isEmpty()) {
                throw new BusinessException(ErrorCode.PACKAGE_NOT_READY,
                        "A shipment on load %s has no sealed parcel".formatted(code));
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_NOT_FULLY_LOADED, "Not scanned onto the vehicle yet: %s. Scan "
                    .formatted(String.join(", ", missing.stream().sorted().toList()))
                    + (mode == DeliveryMode.FULL_TRUCKLOAD ? "them: the plan placed them on this vehicle"
                    : "them, or take their shipment off the manifest"));
        }
        status = LoadStatus.DISPATCHED;
        carrierRepresentative = representative;
        vehiclePlate = plate;
        driverName = driverName == null ? representative : driverName;
        driverPhone = trimmed(handover.driverPhone()) != null ? trimmed(handover.driverPhone()) : driverPhone;
        signatureRef = trimmed(handover.signatureRef());
        handoverNote = trimmed(handover.note());
        dispatchedAt = at;
        dispatchedBy = Objects.requireNonNull(by);
        return leaving;
    }

    /**
     * The label of a shipment on this manifest was reprinted with another COD ({@code LABEL_OUTDATED}, 09 BR-04): the
     * manifest's copy of the label's figure follows, so the load, the handover and the signed manifest show what the
     * parcels' labels say (E2E QA 2026-10-21: they kept the figure from when the shipment was put on the load and
     * flagged the COD as changed since the label). Only before the handover; otherwise nothing changes.
     *
     * @return whether it changed the load
     */
    public boolean relabel(UUID shipmentId, BigDecimal labelCod) {
        Member member = members.get(shipmentId);
        if (!status.isOpen() || member == null || !member.active() || member.codAmount() != null || labelCod == null
                || member.labelCodAmount() != null && member.labelCodAmount().compareTo(labelCod) == 0) {
            return false;
        }
        members.put(shipmentId, new Member(shipmentId, true, member.addedAt(), member.addedBy(), member.removedAt(),
                member.removedBy(), labelCod, null));
        return true;
    }

    /** The COD the order fixed for a shipment at the handover (09 BR-04): the manifest prints it. */
    public void fixCod(UUID shipmentId, BigDecimal cod) {
        Member member = members.get(shipmentId);
        if (member == null) {
            throw new IllegalArgumentException("Shipment " + shipmentId + " is not on load " + code);
        }
        members.put(shipmentId, new Member(shipmentId, member.active(), member.addedAt(), member.addedBy(),
                member.removedAt(), member.removedBy(), member.labelCodAmount(), cod));
    }

    /**
     * Called off before it left (a manifest opened by mistake, the vehicle did not come): every shipment and parcel
     * goes back to the dispatch area, untouched. Already cancelled: nothing changes.
     */
    public boolean cancel(String reason, UUID by, Instant at) {
        if (status == LoadStatus.CANCELLED) {
            return false;
        }
        if (!status.isOpen()) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION,
                    "Load %s is %s; a load that left is not cancelled".formatted(code, status));
        }
        String why = trimmed(reason);
        if (why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cancelling a load says why");
        }
        for (Member member : List.copyOf(members.values())) {
            if (member.active()) {
                members.put(member.shipmentId(), new Member(member.shipmentId(), false, member.addedAt(),
                        member.addedBy(), at, by, member.labelCodAmount(), null));
            }
        }
        for (Loaded parcel : List.copyOf(loaded.values())) {
            if (parcel.active()) {
                loaded.put(parcel.packageId(), unloaded(parcel, by, at));
            }
        }
        status = LoadStatus.CANCELLED;
        cancelledAt = at;
        cancelledBy = by;
        cancelReason = why;
        return true;
    }

    /**
     * <b>Controlled cancel</b> of a vehicle being loaded (SCRUM-474 UNLOAD-DESIGN points 2, 4, 7; kltn-docs 09 BR-14):
     * LOADING &rarr; CANCELLED at once, with a code and a note. Unlike {@link #cancel}, the parcels already on the
     * vehicle are not simply "back at dispatch": each one is left <b>locked</b> - a pending unload with the reason - until
     * it is scanned off ({@link #confirmUnload}) or confirmed off by a warehouse manager
     * ({@link #confirmUnloadWithoutScan}). The shipments leave the manifest; the plan's service opens a PLANNED load
     * in its place when only this vehicle is called off. Cancelled already: nothing changes.
     *
     * @throws BusinessException {@code INVALID_LOAD_TRANSITION} for a parcel load or any status but LOADING;
     *         {@code VALIDATION_FAILED} without a code or a note
     * @return whether it changed the load
     */
    public boolean cancelControlled(UnloadReasonCode reasonCode, String note, UUID by, Instant at) {
        if (status == LoadStatus.CANCELLED) {
            return false;
        }
        requireFullTruckload("be cancelled under control");
        if (status != LoadStatus.LOADING) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is %s; only a vehicle being loaded "
                    + "is cancelled under control (before loading: cancel its booking, or reopen or cancel the "
                    + "plan)").formatted(code, status));
        }
        String why = trimmed(note);
        if (reasonCode == null || why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A controlled cancel names a reason code and a note");
        }
        for (Member member : List.copyOf(members.values())) {
            if (member.active()) {
                members.put(member.shipmentId(), new Member(member.shipmentId(), false, member.addedAt(),
                        member.addedBy(), at, by, member.labelCodAmount(), null));
            }
        }
        for (Loaded parcel : List.copyOf(loaded.values())) {
            if (parcel.active()) {
                loaded.put(parcel.packageId(), unloaded(parcel, by, at));
                unloads.putIfAbsent(parcel.packageId(), new Unload(parcel.packageId(), parcel.shipmentId(), why, at,
                        null, null, reasonCode, false, null));
            }
        }
        status = LoadStatus.CANCELLED;
        cancelledAt = at;
        cancelledBy = by;
        cancelReason = why;
        cancelReasonCode = reasonCode;
        return true;
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    public List<UUID> activeShipments() {
        return members.values().stream().filter(Member::active).map(Member::shipmentId).toList();
    }

    public List<Loaded> activeParcels() {
        return loaded.values().stream().filter(Loaded::active).toList();
    }

    public Collection<Member> members() {
        return List.copyOf(members.values());
    }

    public List<Unload> unloads() {
        return List.copyOf(unloads.values());
    }

    public List<Unload> pendingUnloads() {
        return unloads.values().stream().filter(Unload::pending).toList();
    }

    public Collection<Loaded> loaded() {
        return List.copyOf(loaded.values());
    }

    /** The parcel loaded with {@code scanId}, if this load recorded it. */
    public java.util.Optional<Loaded> scanned(UUID scanId) {
        return loaded.values().stream().filter(l -> scanId.equals(l.scanId())).findFirst();
    }

    /** COD of the shipments on the manifest: as fixed at the handover once dispatched, as labelled before. */
    public BigDecimal codTotal() {
        if (mode == DeliveryMode.FULL_TRUCKLOAD) {
            return BigDecimal.ZERO;   // lead decision 2026-10-21 / LP-2: a full truckload carries no COD
        }
        return members.values().stream().filter(Member::active)
                .map(m -> m.codAmount() != null ? m.codAmount() : m.labelCodAmount() == null ? BigDecimal.ZERO
                        : m.labelCodAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Snapshot snapshot() {
        return new Snapshot(id, code, requestId, warehouseId, mode, carrierId, loadPlanVehicleId, vehiclePlate,
                driverName, driverPhone, status, loadingStartedAt, loadingStartedBy, dispatchedAt, dispatchedBy,
                carrierRepresentative, signatureRef, handoverNote, codTotal(), cancelledAt, cancelledBy, cancelReason,
                version, List.copyOf(members.values()), List.copyOf(loaded.values()), bookedAt, bookedBy,
                bookingCancelledAt, bookingCancelledBy, bookingCancelReason, List.copyOf(unloads.values()),
                cancelReasonCode);
    }

    private void requireOpen(String what) {
        if (!status.isOpen()) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION,
                    "Load %s is %s and cannot %s".formatted(code, status, what));
        }
    }

    private void requireFullTruckload(String what) {
        if (mode != DeliveryMode.FULL_TRUCKLOAD) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION,
                    "Load %s is a parcel load and cannot %s; only a load plan's vehicle can".formatted(code, what));
        }
    }

    private void requireLoading(String what) {
        if (status != LoadStatus.LOADING) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION,
                    "Load %s is %s and cannot %s".formatted(code, status, what));
        }
    }

    private static Loaded unloaded(Loaded parcel, UUID by, Instant at) {
        return new Loaded(parcel.packageId(), parcel.shipmentId(), parcel.scanId(), parcel.loadedAt(),
                parcel.loadedBy(), false, at, by);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public UUID requestId() { return requestId; }
    public UUID warehouseId() { return warehouseId; }
    public DeliveryMode mode() { return mode; }
    public UUID carrierId() { return carrierId; }
    public Instant bookedAt() { return bookedAt; }
    /** The load-plan vehicle a full-truckload load is (SCRUM-287 PR D); null for a parcel load. */
    public UUID loadPlanVehicleId() { return loadPlanVehicleId; }
    public LoadStatus status() { return status; }
    public long version() { return version; }
    public UnloadReasonCode cancelReasonCode() { return cancelReasonCode; }
    public Instant dispatchedAt() { return dispatchedAt; }
    public UUID dispatchedBy() { return dispatchedBy; }
}

package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A load of kltn-docs 09 §4.2: one vehicle of a load plan - the manifest's list, every scan refused with its own code,
 * idempotent repeats, the signature with every parcel of every shipment on board (BR-03), booking, cancellation and
 * unloading. Every order ships as a full truckload (09 BR-08), so every load here is a plan's vehicle.
 */
class DispatchLoadTest {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID GHN = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();

    /** A plan's vehicle, PLANNED. */
    private static DispatchLoad vehicle() {
        return DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-20261022-0001", UUID.randomUUID(), HCM,
                UUID.randomUUID());
    }

    /** A plan's vehicle booked with no plate, its waybill number recorded, and being loaded. */
    private static DispatchLoad loading() {
        DispatchLoad load = vehicle();
        load.book(GHN, null, null, null, "WB-0001", DISPATCHER, NOW);
        load.startLoading(DISPATCHER, NOW);
        return load;
    }

    private static DispatchLoad.ShipmentFacts ready(UUID id) {
        return new DispatchLoad.ShipmentFacts(id, "SO-1-S1", HCM, ShipmentStatus.READY_TO_DISPATCH, null);
    }

    private static final DispatchLoad.Placement HERE = new DispatchLoad.Placement(true, "LP-1", 1, null);

    /** A sealed parcel the plan placed on this vehicle. */
    private static DispatchLoad.ParcelFacts sealed(UUID packageId, DispatchLoad.ShipmentFacts shipment) {
        return placed(packageId, shipment, HERE);
    }

    private static DispatchLoad.ParcelFacts placed(UUID packageId, DispatchLoad.ShipmentFacts shipment,
                                                   DispatchLoad.Placement where) {
        return new DispatchLoad.ParcelFacts(packageId, "PKG-SO-9-" + packageId.toString().substring(0, 4),
                PackageStatus.SEALED, shipment, null, where);
    }

    private static ErrorCode code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static final DispatchLoad.Handover SIGNED = new DispatchLoad.Handover("Nguyen Van Tai", "51C-123.45",
            null, "MF-0001", true, null);

    @Test
    @DisplayName("a plan's vehicle is created PLANNED; a shipment of another warehouse, load or status is refused; the "
            + "same shipment twice changes nothing")
    void theManifestsList() {
        DispatchLoad load = vehicle();
        assertThat(load.status()).isEqualTo(LoadStatus.PLANNED);
        UUID s1 = UUID.randomUUID();
        assertThat(load.addShipment(ready(s1), DISPATCHER, NOW)).isTrue();
        assertThat(load.addShipment(ready(s1), DISPATCHER, NOW)).isFalse();

        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", UUID.randomUUID(),
                ShipmentStatus.READY_TO_DISPATCH, null), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_NOT_FOR_LOAD);
        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", HCM,
                ShipmentStatus.READY_TO_DISPATCH, "LD-20261021-0002"), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD);
        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", HCM,
                ShipmentStatus.CANCELLED, null), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
        assertThat(load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S2", HCM,
                ShipmentStatus.READY_TO_DISPATCH, null), DISPATCHER, NOW)).isTrue();
        assertThat(load.activeShipments()).hasSize(2);
    }

    @Test
    @DisplayName("scans: not sealed / shipment cancelled PACKAGE_NOT_READY, on another load PACKAGE_ALREADY_LOADED, "
            + "of a shipment not on the manifest, of another warehouse or in no load plan PACKAGE_NOT_IN_LOAD; the same "
            + "parcel again is a no-op")
    void everyWrongScanIsRefused() {
        DispatchLoad load = loading();
        UUID s1 = UUID.randomUUID();
        load.addShipment(ready(s1), DISPATCHER, NOW);
        UUID parcel = UUID.randomUUID();

        assertThat(code(() -> load.scan(UUID.randomUUID(), new DispatchLoad.ParcelFacts(parcel, "PKG-1",
                PackageStatus.OPEN, null, null, HERE), DISPATCHER, NOW))).isEqualTo(ErrorCode.PACKAGE_NOT_READY);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(parcel, new DispatchLoad.ShipmentFacts(s1, "S1",
                HCM, ShipmentStatus.CANCELLED, null)), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.PACKAGE_NOT_READY);
        assertThat(code(() -> load.scan(UUID.randomUUID(), new DispatchLoad.ParcelFacts(parcel, "PKG-1",
                PackageStatus.SEALED, ready(s1), "LD-20261021-0009", HERE), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.PACKAGE_ALREADY_LOADED);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), ready(UUID.randomUUID())),
                DISPATCHER, NOW))).isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), new DispatchLoad.ShipmentFacts(
                UUID.randomUUID(), "S7", UUID.randomUUID(), ShipmentStatus.READY_TO_DISPATCH, null)), DISPATCHER,
                NOW))).isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        Throwable unplanned = catchThrowable(() -> load.scan(UUID.randomUUID(), placed(parcel, ready(s1), null),
                DISPATCHER, NOW));
        assertThat(((BusinessException) unplanned).errorCode()).isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        assertThat(unplanned.getMessage()).contains("in no load plan");
        assertThat(load.activeParcels()).isEmpty();

        assertThat(load.scan(UUID.randomUUID(), sealed(parcel, ready(s1)), DISPATCHER, NOW)).isTrue();
        assertThat(load.scan(UUID.randomUUID(), sealed(parcel, ready(s1)), DISPATCHER, NOW)).isFalse();
        assertThat(load.activeParcels()).hasSize(1);
        assertThat(load.unscan(parcel, DISPATCHER, NOW)).isTrue();
        assertThat(load.unscan(parcel, DISPATCHER, NOW)).isFalse();
        UUID again = UUID.randomUUID();
        assertThat(load.scan(again, sealed(parcel, ready(s1)), DISPATCHER, NOW)).isTrue();
        assertThat(load.scanned(again)).isPresent();
    }

    @Test
    @DisplayName("3-5: a carrier's driver goes on a booked or loading vehicle and comes off it; once the vehicle left "
            + "only the same driver changes nothing; with no vehicle booked, a driver is not expected")
    void theCarriersDriver() {
        DispatchLoad load = vehicle();
        assertThat(code(() -> load.carrierAssignedDriver("Tài", "0900"))).isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        assertThat(code(load::carrierReleasedDriver)).isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        load.book(GHN, null, null, null, "AHA-1", DISPATCHER, NOW);
        assertThat(load.carrierReleasedDriver()).isFalse();
        assertThat(load.carrierAssignedDriver(" Tài ", "0900")).isTrue();
        assertThat(load.snapshot().driverName()).isEqualTo("Tài");
        assertThat(load.carrierAssignedDriver("Tài", "0900")).isFalse();
        assertThat(load.carrierReleasedDriver()).isTrue();
        assertThat(load.snapshot().driverName()).isNull();
        assertThat(load.snapshot().driverPhone()).isNull();
        assertThat(load.snapshot().waybillNo()).isEqualTo("AHA-1");

        DispatchLoad left = loading();
        assertThat(left.carrierAssignedDriver("Hùng", "0911")).isTrue();
    }

    @Test
    @DisplayName("waybill number (09 §4.4, BR-23, BR-24): booked with it or recorded after, kept while the carrier stays, "
            + "gone with the booking, required to leave, corrected after leaving only with a reason")
    void theWaybillNumber() {
        DispatchLoad load = vehicle();
        assertThat(code(() -> load.recordWaybill("WB-1", null, null, null, null)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        assertThat(load.book(GHN, "51C-1", null, null, " WB-1 ", DISPATCHER, NOW)).isTrue();
        assertThat(load.waybillNo()).isEqualTo("WB-1");
        // Booked again with the same carrier and no number: kept; with another carrier: a number is one carrier's.
        assertThat(load.book(GHN, "51C-2", null, null, DISPATCHER, NOW)).isTrue();
        assertThat(load.waybillNo()).isEqualTo("WB-1");
        assertThat(load.book(UUID.randomUUID(), "51C-2", null, null, DISPATCHER, NOW)).isTrue();
        assertThat(load.waybillNo()).isNull();
        assertThat(load.book(GHN, "51C-2", null, null, DISPATCHER, NOW)).isTrue();
        assertThat(load.recordWaybill("WB-2", null, "Tài", "0900", null)).isTrue();
        assertThat(load.snapshot().waybillNo()).isEqualTo("WB-2");
        assertThat(load.snapshot().driverName()).isEqualTo("Tài");
        assertThat(load.snapshot().vehiclePlate()).isEqualTo("51C-2");
        assertThat(load.recordWaybill("WB-2", null, null, null, null)).isFalse();
        assertThat(code(() -> load.recordWaybill("WB 3", null, null, null, null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> load.recordWaybill(" ", null, null, null, null))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> load.recordWaybill("W".repeat(65), null, null, null, null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(load.cancelBooking("Xe hỏng", DISPATCHER, NOW)).isTrue();
        assertThat(load.waybillNo()).isNull();

        DispatchLoad noNumber = vehicle();
        UUID s1 = UUID.randomUUID();
        UUID parcel = UUID.randomUUID();
        noNumber.addShipment(ftl(s1, null), DISPATCHER, NOW);
        noNumber.book(GHN, "51C-1", null, null, DISPATCHER, NOW);
        noNumber.startLoading(DISPATCHER, NOW);
        noNumber.scan(UUID.randomUUID(), placed(parcel, ftl(s1, null), HERE), DISPATCHER, NOW);
        assertThat(code(() -> noNumber.handOver(SIGNED, Map.of(s1, Set.of(parcel)), Map.of(), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.LOAD_WAYBILL_MISSING);
        assertThat(noNumber.recordWaybill("WB-9", null, null, null, null)).isTrue();
        assertThat(noNumber.handOver(SIGNED, Map.of(s1, Set.of(parcel)), Map.of(), DISPATCHER, NOW)).containsExactly(s1);

        // After the vehicle left: a correction says why, and changes the number only - the plate and driver are on
        // the signed manifest.
        assertThat(code(() -> noNumber.recordWaybill("WB-10", null, null, null, " ")))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> noNumber.recordWaybill("WB-10", "", null, null, "Hãng gửi lại mã")))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> noNumber.recordWaybill("WB-10", null, "Tài xế khác", null, "Hãng gửi lại mã")))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(noNumber.recordWaybill("WB-9", null, null, null, null)).isFalse();
        assertThat(noNumber.recordWaybill("WB-10", null, null, null, "Hãng gửi lại mã")).isTrue();
        assertThat(noNumber.waybillNo()).isEqualTo("WB-10");
        assertThat(DispatchLoad.restore(noNumber.snapshot()).waybillNo()).isEqualTo("WB-10");
    }

    @Test
    @DisplayName("handover: unsigned 400, nothing on it LOAD_EMPTY, a parcel left behind LOAD_NOT_FULLY_LOADED naming "
            + "it, no plate refused; a shipment withdrawn from the manifest lets the rest go; again: nothing changes")
    void theSignature() {
        DispatchLoad empty = loading();
        assertThat(code(() -> empty.handOver(SIGNED, Map.of(), Map.of(), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.LOAD_EMPTY);

        DispatchLoad load = loading();
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        load.addShipment(ready(s1), DISPATCHER, NOW);
        load.addShipment(ready(s2), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), sealed(p1, ready(s1)), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), sealed(p2, ready(s1)), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), sealed(p3, ready(s2)), DISPATCHER, NOW);
        load.unscan(p3, DISPATCHER, NOW);
        Map<UUID, Set<UUID>> parcels = Map.of(s1, Set.of(p1, p2), s2, Set.of(p3));
        Map<UUID, String> labels = Map.of(p1, "PKG-A-1", p2, "PKG-A-2", p3, "PKG-B-1");

        assertThat(code(() -> load.handOver(new DispatchLoad.Handover("Tai", "51C", null, null, false, null),
                parcels, labels, DISPATCHER, NOW))).isEqualTo(ErrorCode.HANDOVER_NOT_SIGNED);
        Throwable missing = catchThrowable(() -> load.handOver(SIGNED, parcels, labels, DISPATCHER, NOW));
        assertThat(((BusinessException) missing).errorCode()).isEqualTo(ErrorCode.LOAD_NOT_FULLY_LOADED);
        assertThat(missing.getMessage()).contains("PKG-B-1").doesNotContain("PKG-A-1");
        assertThat(code(() -> load.handOver(new DispatchLoad.Handover("Tai", null, null, null, true, null), parcels,
                labels, DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // s2's parcel is off the vehicle already, so withdrawing it leaves nothing to unload.
        assertThat(load.withdrawShipment(s2, "The order was cancelled", NOW)).isTrue();
        assertThat(load.pendingUnloads()).isEmpty();
        assertThat(load.handOver(SIGNED, parcels, labels, DISPATCHER, NOW)).containsExactly(s1);
        assertThat(load.status()).isEqualTo(LoadStatus.DISPATCHED);
        assertThat(load.handOver(SIGNED, parcels, labels, DISPATCHER, NOW)).isEmpty();
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(p3, ready(s2)), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        assertThat(code(() -> load.cancel("too late", DISPATCHER, NOW))).isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        DispatchLoad.Snapshot s = load.snapshot();
        assertThat(s.carrierRepresentative()).isEqualTo("Nguyen Van Tai");
        assertThat(s.vehiclePlate()).isEqualTo("51C-123.45");
        assertThat(s.signatureRef()).isEqualTo("MF-0001");
        assertThat(s.dispatchedBy()).isEqualTo(DISPATCHER);
    }

    @Test
    @DisplayName("cancelled before it left: every shipment and parcel goes back; it needs a reason; again: no-op")
    void cancelled() {
        DispatchLoad load = loading();
        UUID s1 = UUID.randomUUID();
        load.addShipment(ready(s1), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), ready(s1)), DISPATCHER, NOW);
        assertThat(code(() -> load.cancel(" ", DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(load.cancel("vehicle did not come", DISPATCHER, NOW)).isTrue();
        assertThat(load.cancel("again", DISPATCHER, NOW)).isFalse();
        assertThat(load.activeShipments()).isEmpty();
        assertThat(load.activeParcels()).isEmpty();
        assertThat(code(() -> load.addShipment(ready(UUID.randomUUID()), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
    }

    @Test
    @DisplayName("restored from its snapshot unchanged")
    void roundTrip() {
        DispatchLoad load = loading();
        UUID s1 = UUID.randomUUID();
        load.addShipment(ready(s1), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), ready(s1)), DISPATCHER, NOW);
        DispatchLoad back = DispatchLoad.restore(load.snapshot());
        assertThat(back.snapshot()).isEqualTo(load.snapshot());
        assertThat(back.activeParcels()).hasSize(1);
        assertThat(List.copyOf(back.members())).hasSize(1);
    }

    // ---- booking and loading (SCRUM-392) ---------------------------------------------------------------------------

    private static DispatchLoad.ShipmentFacts ftl(UUID id, String otherLoad) {
        return new DispatchLoad.ShipmentFacts(id, "SO-9-S1", HCM, ShipmentStatus.READY_TO_DISPATCH, otherLoad);
    }

    @Test
    @DisplayName("PLANNED -> booked (again: replaced; same: no-op) -> booking cancelled with a reason back to PLANNED "
            + "-> booked -> LOADING; loading before booking, booking or cancelling the booking once loading are "
            + "INVALID_LOAD_TRANSITION")
    void bookingAndLoading() {
        DispatchLoad load = vehicle();
        UUID ghn = UUID.randomUUID();
        UUID ghtk = UUID.randomUUID();
        assertThat(code(() -> load.startLoading(DISPATCHER, NOW))).isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        assertThat(load.cancelBooking("nothing booked", DISPATCHER, NOW)).isFalse();
        assertThat(load.book(ghn, "51C-1", "Tai", null, DISPATCHER, NOW)).isTrue();
        assertThat(load.status()).isEqualTo(LoadStatus.VEHICLE_BOOKED);
        assertThat(load.book(ghn, "51C-1", "Tai", null, DISPATCHER, NOW)).isFalse();
        assertThat(load.book(ghtk, "51C-2", null, null, DISPATCHER, NOW)).isTrue();
        assertThat(load.carrierId()).isEqualTo(ghtk);
        assertThat(code(() -> load.cancelBooking(" ", DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(load.cancelBooking("carrier has no truck", DISPATCHER, NOW)).isTrue();
        DispatchLoad.Snapshot cancelled = load.snapshot();
        assertThat(cancelled.status()).isEqualTo(LoadStatus.PLANNED);
        assertThat(cancelled.carrierId()).isNull();
        assertThat(cancelled.vehiclePlate()).isNull();
        assertThat(cancelled.bookedAt()).isNull();
        assertThat(cancelled.bookingCancelReason()).isEqualTo("carrier has no truck");

        assertThat(load.book(ghn, null, null, null, DISPATCHER, NOW)).isTrue();
        assertThat(load.startLoading(DISPATCHER, NOW)).isTrue();
        assertThat(load.startLoading(DISPATCHER, NOW)).isFalse();
        assertThat(load.status()).isEqualTo(LoadStatus.LOADING);
        assertThat(code(() -> load.cancelBooking("too late", DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        assertThat(code(() -> load.book(ghtk, null, null, null, DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
    }

    @Test
    @DisplayName("a shipment goes on the unbooked vehicle's manifest; a parcel placed on another vehicle is "
            + "PACKAGE_NOT_IN_LOAD naming vehicle and load; the handover checks only the parcels given for this "
            + "vehicle")
    void scanningAVehicle() {
        DispatchLoad load = vehicle();
        UUID s1 = UUID.randomUUID();
        DispatchLoad.ShipmentFacts shipment = ftl(s1, null);
        assertThat(load.addShipment(shipment, DISPATCHER, NOW)).isTrue();
        assertThat(code(() -> load.addShipment(ftl(UUID.randomUUID(), "LD-OTHER"), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD);
        load.book(GHN, "51C-1", null, null, "WB-0001", DISPATCHER, NOW);
        load.startLoading(DISPATCHER, NOW);
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        Throwable wrong = catchThrowable(() -> load.scan(UUID.randomUUID(), placed(theirs, shipment,
                new DispatchLoad.Placement(false, "LP-1", 2, "LD-20261022-0002")), DISPATCHER, NOW));
        assertThat(((BusinessException) wrong).errorCode()).isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        assertThat(wrong.getMessage()).contains("vehicle 2").contains("LD-20261022-0002");
        assertThat(((BusinessException) wrong).fieldErrors()).extracting(f -> f.field() + "=" + f.message())
                .contains("vehicleSeq=2", "loadCode=LD-20261022-0002");
        assertThat(code(() -> load.scan(UUID.randomUUID(), placed(theirs, shipment,
                new DispatchLoad.Placement(false, null, null, null)), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        assertThat(load.scan(UUID.randomUUID(), placed(mine, shipment, HERE), DISPATCHER, NOW)).isTrue();
        // Only this vehicle's parcels are expected; the shipment's parcel on vehicle 2 is not.
        assertThat(load.handOver(SIGNED, Map.of(s1, Set.of(mine)), Map.of(mine, "PKG-1"), DISPATCHER, NOW))
                .containsExactly(s1);
    }

    @Test
    @DisplayName("an order cancelled while its parcels are on board leaves the manifest; the scanned parcels are to be "
            + "unloaded and the handover is LOAD_UNLOAD_PENDING until they are; not scanned or not loading: nothing to "
            + "unload")
    void unloadingACancelledOrder() {
        DispatchLoad load = vehicle();
        UUID doomed = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        load.addShipment(ftl(doomed, null), DISPATCHER, NOW);
        load.addShipment(ftl(kept, null), DISPATCHER, NOW);
        assertThat(load.withdrawShipment(UUID.randomUUID(), "x", NOW)).isFalse();
        load.book(UUID.randomUUID(), "51C-1", null, null, "WB-0001", DISPATCHER, NOW);
        load.startLoading(DISPATCHER, NOW);
        UUID onBoard = UUID.randomUUID();
        UUID keptParcel = UUID.randomUUID();
        load.scan(UUID.randomUUID(), placed(onBoard, ftl(doomed, null), HERE), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), placed(keptParcel, ftl(kept, null), HERE), DISPATCHER, NOW);

        assertThat(load.withdrawShipment(doomed, "The order was cancelled", NOW)).isTrue();
        assertThat(load.withdrawShipment(doomed, "again", NOW)).isFalse();
        assertThat(load.activeShipments()).containsExactly(kept);
        assertThat(load.pendingUnloads()).singleElement().satisfies(u -> {
            assertThat(u.packageId()).isEqualTo(onBoard);
            assertThat(u.reason()).isEqualTo("The order was cancelled");
        });
        Map<UUID, Set<UUID>> parcels = Map.of(kept, Set.of(keptParcel));
        Throwable pending = catchThrowable(() -> load.handOver(SIGNED, parcels, Map.of(), DISPATCHER, NOW));
        assertThat(((BusinessException) pending).errorCode()).isEqualTo(ErrorCode.LOAD_UNLOAD_PENDING);
        assertThat(code(() -> load.confirmUnload(keptParcel, "PKG-K", DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        assertThat(load.confirmUnload(onBoard, "PKG-D", DISPATCHER, NOW)).isTrue();
        assertThat(load.confirmUnload(onBoard, "PKG-D", DISPATCHER, NOW)).isFalse();
        assertThat(load.handOver(SIGNED, parcels, Map.of(), DISPATCHER, NOW)).containsExactly(kept);

        // Restored with its unloads.
        DispatchLoad back = DispatchLoad.restore(load.snapshot());
        assertThat(back.snapshot()).isEqualTo(load.snapshot());
        assertThat(back.unloads()).hasSize(1);

        // Booked but not loading: nothing is on board, nothing to unload.
        DispatchLoad booked = vehicle();
        UUID s = UUID.randomUUID();
        booked.addShipment(ftl(s, null), DISPATCHER, NOW);
        booked.book(UUID.randomUUID(), null, null, null, DISPATCHER, NOW);
        assertThat(booked.withdrawShipment(s, null, NOW)).isTrue();
        assertThat(booked.pendingUnloads()).isEmpty();
    }

    @Test
    @DisplayName("UNLOAD-DESIGN points 2, 4, 5, 7: a vehicle being loaded is cancelled under control - CANCELLED at once "
            + "with code, note, actor and time; its shipments leave the manifest; every scanned parcel is left locked "
            + "as a pending unload carrying the code, until scanned off or confirmed off without a scan (overridden, "
            + "note); an unload of a cancelled order keeps its own (no code) reason; again changes nothing")
    void cancelledUnderControl() {
        DispatchLoad load = vehicle();
        UUID doomed = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        load.addShipment(ftl(doomed, null), DISPATCHER, NOW);
        load.addShipment(ftl(kept, null), DISPATCHER, NOW);
        assertThat(code(() -> load.cancelControlled(UnloadReasonCode.XE_HONG, "axle", DISPATCHER, NOW)))
                .as("not loading yet: the booking is cancelled the plain way").isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        load.book(UUID.randomUUID(), "51C-1", null, null, DISPATCHER, NOW);
        load.startLoading(DISPATCHER, NOW);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        load.scan(UUID.randomUUID(), placed(p1, ftl(doomed, null), HERE), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), placed(p2, ftl(kept, null), HERE), DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), placed(p3, ftl(kept, null), HERE), DISPATCHER, NOW);
        load.withdrawShipment(doomed, "The order was cancelled", NOW);   // its parcel is to unload already (SCRUM-392)

        assertThat(code(() -> load.cancelControlled(null, "axle", DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> load.cancelControlled(UnloadReasonCode.XE_HONG, " ", DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        Instant later = NOW.plusSeconds(60);
        assertThat(load.cancelControlled(UnloadReasonCode.XE_HONG, " the rear axle broke ", DISPATCHER, later)).isTrue();
        assertThat(load.status()).isEqualTo(LoadStatus.CANCELLED);
        assertThat(load.cancelReasonCode()).isEqualTo(UnloadReasonCode.XE_HONG);
        assertThat(load.snapshot().cancelReason()).isEqualTo("the rear axle broke");
        assertThat(load.snapshot().cancelledBy()).isEqualTo(DISPATCHER);
        assertThat(load.snapshot().cancelledAt()).isEqualTo(later);
        assertThat(load.activeShipments()).isEmpty();
        assertThat(load.activeParcels()).isEmpty();
        assertThat(load.pendingUnloads()).extracting(DispatchLoad.Unload::packageId)
                .containsExactlyInAnyOrder(p1, p2, p3);
        assertThat(load.pendingUnloads()).filteredOn(u -> u.packageId().equals(p1)).singleElement().satisfies(u -> {
            assertThat(u.reasonCode()).as("the cancelled order's parcel keeps its own reason").isNull();
            assertThat(u.reason()).isEqualTo("The order was cancelled");
        });
        assertThat(load.pendingUnloads()).filteredOn(u -> u.packageId().equals(p2)).singleElement().satisfies(u -> {
            assertThat(u.reasonCode()).isEqualTo(UnloadReasonCode.XE_HONG);
            assertThat(u.reason()).isEqualTo("the rear axle broke");
        });
        assertThat(load.cancelControlled(UnloadReasonCode.KHAC, "again", DISPATCHER, later)).isFalse();
        assertThat(load.cancelReasonCode()).isEqualTo(UnloadReasonCode.XE_HONG);

        // Scanned off (by the dispatcher), then one confirmed off without a scan (its label is damaged).
        assertThat(load.confirmUnload(p2, "PKG-2", DISPATCHER, later)).isTrue();
        UUID manager = UUID.randomUUID();
        assertThat(code(() -> load.confirmUnloadWithoutScan(UUID.randomUUID(), "PKG-X", null, manager, later)))
                .isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        assertThat(load.confirmUnloadWithoutScan(p3, "PKG-3", " torn label ", manager, later)).isTrue();
        assertThat(load.confirmUnloadWithoutScan(p3, "PKG-3", "again", manager, later)).isFalse();
        assertThat(load.unloads()).filteredOn(u -> u.packageId().equals(p3)).singleElement().satisfies(u -> {
            assertThat(u.overridden()).isTrue();
            assertThat(u.overrideNote()).isEqualTo("torn label");
            assertThat(u.unloadedBy()).isEqualTo(manager);
        });
        assertThat(load.unloads()).filteredOn(u -> u.packageId().equals(p2)).singleElement()
                .satisfies(u -> assertThat(u.overridden()).isFalse());
        assertThat(load.pendingUnloads()).extracting(DispatchLoad.Unload::packageId).containsExactly(p1);
        // Nothing of the cancelled load is ever handed over, and it is restored with all of it.
        assertThat(code(() -> load.handOver(SIGNED, Map.of(), Map.of(), DISPATCHER, later)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        assertThat(DispatchLoad.restore(load.snapshot()).snapshot()).isEqualTo(load.snapshot());
    }

    @Test
    @DisplayName("UNLOAD-DROP: a vehicle still PLANNED is dropped from its plan - CANCELLED with code, note, actor and "
            + "time, its shipments leave the manifest, nothing is locked (nothing was scanned); a code and a note are "
            + "required; a booked or loading vehicle is refused; again changes nothing")
    void droppedFromItsPlan() {
        DispatchLoad load = vehicle();
        UUID shipment = UUID.randomUUID();
        load.addShipment(ftl(shipment, null), DISPATCHER, NOW);
        assertThat(code(() -> load.dropFromPlan(null, "no truck", DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> load.dropFromPlan(UnloadReasonCode.KHAC, "  ", DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(load.status()).isEqualTo(LoadStatus.PLANNED);

        Instant later = NOW.plusSeconds(60);
        assertThat(load.dropFromPlan(UnloadReasonCode.KHAC, " no truck is free ", DISPATCHER, later)).isTrue();
        assertThat(load.status()).isEqualTo(LoadStatus.CANCELLED);
        assertThat(load.cancelReasonCode()).isEqualTo(UnloadReasonCode.KHAC);
        assertThat(load.snapshot().cancelReason()).isEqualTo("no truck is free");
        assertThat(load.snapshot().cancelledBy()).isEqualTo(DISPATCHER);
        assertThat(load.snapshot().cancelledAt()).isEqualTo(later);
        assertThat(load.activeShipments()).isEmpty();
        assertThat(load.pendingUnloads()).isEmpty();
        assertThat(load.dropFromPlan(UnloadReasonCode.XE_HONG, "again", DISPATCHER, later)).isFalse();
        assertThat(load.cancelReasonCode()).isEqualTo(UnloadReasonCode.KHAC);
        assertThat(DispatchLoad.restore(load.snapshot()).cancelReasonCode()).isEqualTo(UnloadReasonCode.KHAC);

        DispatchLoad booked = vehicle();
        booked.book(UUID.randomUUID(), "51C-1", null, null, DISPATCHER, NOW);
        assertThat(code(() -> booked.dropFromPlan(UnloadReasonCode.KHAC, "x", DISPATCHER, NOW)))
                .as("a booked vehicle has its booking cancelled first").isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        booked.startLoading(DISPATCHER, NOW);
        assertThat(code(() -> booked.dropFromPlan(UnloadReasonCode.KHAC, "x", DISPATCHER, NOW)))
                .as("a vehicle being loaded is cancelled under control").isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
    }
}

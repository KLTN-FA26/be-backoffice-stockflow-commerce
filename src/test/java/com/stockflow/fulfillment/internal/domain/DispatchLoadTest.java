package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A load of kltn-docs 09 §4.1: the manifest's list, every scan refused with its own code, idempotent repeats, the
 * signature with every parcel of every shipment on board (BR-03), cancellation.
 */
class DispatchLoadTest {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID GHN = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();

    private static DispatchLoad open() {
        return DispatchLoad.openParcel(UUID.randomUUID(), "LD-20261021-0001", UUID.randomUUID(), HCM, GHN, null,
                null, null, DISPATCHER, NOW);
    }

    private static DispatchLoad.ShipmentFacts ready(UUID id) {
        return new DispatchLoad.ShipmentFacts(id, "SO-1-S1", HCM, DeliveryMode.PARCEL, ShipmentStatus.READY_TO_DISPATCH,
                null, null);
    }

    private static DispatchLoad.ParcelFacts sealed(UUID packageId, DispatchLoad.ShipmentFacts shipment) {
        return new DispatchLoad.ParcelFacts(packageId, "PKG-SO-1-1", PackageStatus.SEALED, shipment, null);
    }

    private static ErrorCode code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static final DispatchLoad.Handover SIGNED = new DispatchLoad.Handover("Nguyen Van Tai", "51C-123.45",
            null, "MF-0001", true, null);

    @Test
    @DisplayName("opened LOADING for one carrier; a shipment of another warehouse, carrier, mode, load or status is "
            + "refused; the same shipment twice changes nothing")
    void theManifestsList() {
        DispatchLoad load = open();
        assertThat(load.status()).isEqualTo(LoadStatus.LOADING);
        UUID s1 = UUID.randomUUID();
        assertThat(load.addShipment(ready(s1), BigDecimal.TEN, DISPATCHER, NOW)).isTrue();
        assertThat(load.addShipment(ready(s1), BigDecimal.TEN, DISPATCHER, NOW)).isFalse();

        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", UUID.randomUUID(),
                DeliveryMode.PARCEL, ShipmentStatus.READY_TO_DISPATCH, null, null), null, DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_NOT_FOR_LOAD);
        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", HCM,
                DeliveryMode.PARCEL, ShipmentStatus.READY_TO_DISPATCH, UUID.randomUUID(), null), null, DISPATCHER,
                NOW))).isEqualTo(ErrorCode.SHIPMENT_OTHER_CARRIER);
        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", HCM,
                DeliveryMode.FULL_TRUCKLOAD, ShipmentStatus.READY_TO_DISPATCH, null, null), null, DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.PACKAGE_REQUIRES_LOAD_PLAN);
        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", HCM,
                DeliveryMode.PARCEL, ShipmentStatus.READY_TO_DISPATCH, null, "LD-20261021-0002"), null, DISPATCHER,
                NOW))).isEqualTo(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD);
        assertThat(code(() -> load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S", HCM,
                DeliveryMode.PARCEL, ShipmentStatus.CANCELLED, null, null), null, DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
        // A shipment chosen for this carrier is welcome.
        assertThat(load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "S2", HCM, DeliveryMode.PARCEL,
                ShipmentStatus.READY_TO_DISPATCH, GHN, null), BigDecimal.ONE, DISPATCHER, NOW)).isTrue();
        assertThat(load.activeShipments()).hasSize(2);
        assertThat(load.codTotal()).isEqualByComparingTo("11");
    }

    @Test
    @DisplayName("scans: not sealed / shipment cancelled PACKAGE_NOT_READY, on another load PACKAGE_ALREADY_LOADED, "
            + "of a shipment not on the manifest PACKAGE_NOT_IN_LOAD, other carrier PACKAGE_OTHER_CARRIER, FTL "
            + "PACKAGE_REQUIRES_LOAD_PLAN; the same parcel again is a no-op")
    void everyWrongScanIsRefused() {
        DispatchLoad load = open();
        UUID s1 = UUID.randomUUID();
        load.addShipment(ready(s1), null, DISPATCHER, NOW);
        UUID parcel = UUID.randomUUID();

        assertThat(code(() -> load.scan(UUID.randomUUID(), new DispatchLoad.ParcelFacts(parcel, "PKG-1",
                PackageStatus.OPEN, null, null), DISPATCHER, NOW))).isEqualTo(ErrorCode.PACKAGE_NOT_READY);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(parcel, new DispatchLoad.ShipmentFacts(s1, "S1",
                HCM, DeliveryMode.PARCEL, ShipmentStatus.CANCELLED, null, null)), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.PACKAGE_NOT_READY);
        assertThat(code(() -> load.scan(UUID.randomUUID(), new DispatchLoad.ParcelFacts(parcel, "PKG-1",
                PackageStatus.SEALED, ready(s1), "LD-20261021-0009"), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.PACKAGE_ALREADY_LOADED);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), ready(UUID.randomUUID())),
                DISPATCHER, NOW))).isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), new DispatchLoad.ShipmentFacts(
                UUID.randomUUID(), "S9", HCM, DeliveryMode.PARCEL, ShipmentStatus.READY_TO_DISPATCH,
                UUID.randomUUID(), null)), DISPATCHER, NOW))).isEqualTo(ErrorCode.PACKAGE_OTHER_CARRIER);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), new DispatchLoad.ShipmentFacts(
                UUID.randomUUID(), "S8", HCM, DeliveryMode.FULL_TRUCKLOAD, ShipmentStatus.READY_TO_DISPATCH, null,
                null)), DISPATCHER, NOW))).isEqualTo(ErrorCode.PACKAGE_REQUIRES_LOAD_PLAN);
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), new DispatchLoad.ShipmentFacts(
                UUID.randomUUID(), "S7", UUID.randomUUID(), DeliveryMode.PARCEL, ShipmentStatus.READY_TO_DISPATCH,
                null, null)), DISPATCHER, NOW))).isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
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
    @DisplayName("handover: unsigned 400, nothing on it LOAD_EMPTY, a parcel left behind LOAD_NOT_FULLY_LOADED naming "
            + "it, no plate refused; taking the shipment off lets the rest go; again: nothing changes")
    void theSignature() {
        DispatchLoad empty = open();
        assertThat(code(() -> empty.handOver(SIGNED, Map.of(), Map.of(), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.LOAD_EMPTY);

        DispatchLoad load = open();
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        load.addShipment(ready(s1), BigDecimal.ZERO, DISPATCHER, NOW);
        load.addShipment(ready(s2), BigDecimal.ZERO, DISPATCHER, NOW);
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

        assertThat(load.removeShipment(s2, DISPATCHER, NOW)).isTrue();
        assertThat(load.handOver(SIGNED, parcels, labels, DISPATCHER, NOW)).containsExactly(s1);
        assertThat(load.status()).isEqualTo(LoadStatus.DISPATCHED);
        assertThat(load.handOver(SIGNED, parcels, labels, DISPATCHER, NOW)).isEmpty();
        assertThat(code(() -> load.scan(UUID.randomUUID(), sealed(p3, ready(s2)), DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        assertThat(code(() -> load.cancel("too late", DISPATCHER, NOW))).isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        load.fixCod(s1, new BigDecimal("700"));
        assertThat(load.codTotal()).isEqualByComparingTo("700");
        DispatchLoad.Snapshot s = load.snapshot();
        assertThat(s.carrierRepresentative()).isEqualTo("Nguyen Van Tai");
        assertThat(s.vehiclePlate()).isEqualTo("51C-123.45");
        assertThat(s.signatureRef()).isEqualTo("MF-0001");
        assertThat(s.dispatchedBy()).isEqualTo(DISPATCHER);
    }

    @Test
    @DisplayName("cancelled before it left: every shipment and parcel goes back; it needs a reason; again: no-op")
    void cancelled() {
        DispatchLoad load = open();
        UUID s1 = UUID.randomUUID();
        load.addShipment(ready(s1), null, DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), ready(s1)), DISPATCHER, NOW);
        assertThat(code(() -> load.cancel(" ", DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(load.cancel("vehicle did not come", DISPATCHER, NOW)).isTrue();
        assertThat(load.cancel("again", DISPATCHER, NOW)).isFalse();
        assertThat(load.activeShipments()).isEmpty();
        assertThat(load.activeParcels()).isEmpty();
        assertThat(code(() -> load.addShipment(ready(UUID.randomUUID()), null, DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
    }

    @Test
    @DisplayName("restored from its snapshot unchanged")
    void roundTrip() {
        DispatchLoad load = open();
        UUID s1 = UUID.randomUUID();
        load.addShipment(ready(s1), BigDecimal.TEN, DISPATCHER, NOW);
        load.scan(UUID.randomUUID(), sealed(UUID.randomUUID(), ready(s1)), DISPATCHER, NOW);
        DispatchLoad back = DispatchLoad.restore(load.snapshot());
        assertThat(back.snapshot()).isEqualTo(load.snapshot());
        assertThat(back.activeParcels()).hasSize(1);
        assertThat(List.copyOf(back.members())).hasSize(1);
    }
}

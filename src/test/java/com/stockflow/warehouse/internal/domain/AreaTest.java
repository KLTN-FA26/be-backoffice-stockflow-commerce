package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Area aggregate: an area and, if it holds stock, its storage location - which shares the
 * area's status (issue #18 D8) and never goes away once there (D10).
 */
class AreaTest {

    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Footprint FLOOR = new Footprint(new BigDecimal("40"), new BigDecimal("2"),
            new BigDecimal("15"), new BigDecimal("8"), 0);
    private static final LocationSettings SETTINGS = new LocationSettings(500, new BigDecimal("2000"), false, true);

    private static UUID newId() {
        return new UUID(0x0190a000_0000_7000L, 0x8000_0000_0000_0000L | SEQUENCE.incrementAndGet());
    }

    private static AreaDetails details(AreaType type) {
        return new AreaDetails(type, "Khu " + type, FLOOR, false, null, SETTINGS);
    }

    private static Area area(String code, AreaType type) {
        return Area.create(newId(), WAREHOUSE, code, details(type), "hn", AreaTest::newId);
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Test
    @DisplayName("a storage area owns an ACTIVE AREA location coded prefix-area, NORMAL by default (#18 D9)")
    void storageAreaOwnsALocation() {
        Area area = area("rcv02", AreaType.RECEIVING);

        assertThat(area.code()).isEqualTo("RCV02");
        assertThat(area.status()).isEqualTo(LocationStatus.ACTIVE);
        StorageLocation location = area.location();
        assertThat(location.kind()).isEqualTo(StorageLocationKind.AREA);
        assertThat(location.locationCode()).isEqualTo("HN-RCV02");
        assertThat(location.status()).isEqualTo(LocationStatus.ACTIVE);
        assertThat(location.storageClass()).isEqualTo(StorageClass.NORMAL);
        assertThat(location.capacityUnits()).isEqualTo(500);
        assertThat(location.id()).isNotEqualTo(area.id());
    }

    @Test
    @DisplayName("a NON_STORAGE area has no location, and location settings sent with it are dropped")
    void nonStorageAreaHasNoLocation() {
        Area office = area("office", AreaType.NON_STORAGE);

        assertThat(office.location()).isNull();
        assertThat(details(AreaType.NON_STORAGE).settings()).isNull();
    }

    @Test
    @DisplayName("a storage type needs location settings; a code must fit a location code")
    void refusesBadInput() {
        assertThat(errorOf(() -> new AreaDetails(AreaType.OVERFLOW, "Khu", FLOOR, false, null, null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorOf(() -> area("RCV-01", AreaType.RECEIVING))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a storage area cannot become NON_STORAGE, and nothing changes (#18 D10)")
    void storageNeverBecomesNonStorage() {
        Area area = area("rcv02", AreaType.RECEIVING);

        assertThat(errorOf(() -> area.update(details(AreaType.NON_STORAGE), 0, "hn", AreaTest::newId)))
                .isEqualTo(ErrorCode.AREA_TYPE_CHANGE_NOT_ALLOWED);
        assertThat(area.type()).isEqualTo(AreaType.RECEIVING);
        assertThat(area.location()).isNotNull();
    }

    @Test
    @DisplayName("NON_STORAGE to RECEIVING gets a location, in the area's own status (#18 D8, D10)")
    void nonStorageBecomesStorage() {
        Area office = area("office", AreaType.NON_STORAGE);
        office.changeStatus(LocationStatus.MAINTENANCE);

        office.update(details(AreaType.RECEIVING), 0, "hn", AreaTest::newId);

        assertThat(office.location().locationCode()).isEqualTo("HN-OFFICE");
        assertThat(office.location().status()).isEqualTo(LocationStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("an update keeps the code and location code and changes the location's settings")
    void updatesTheLocationInPlace() {
        Area area = area("rcv02", AreaType.RECEIVING);
        UUID locationId = area.location().id();

        area.update(new AreaDetails(AreaType.OVERFLOW, "Khu tràn", FLOOR, true, StorageClass.OVERSIZE,
                new LocationSettings(null, null, true, false)), 0, "hn", AreaTest::newId);

        assertThat(area.code()).isEqualTo("RCV02");
        assertThat(area.location().id()).isEqualTo(locationId);
        assertThat(area.location().locationCode()).isEqualTo("HN-RCV02");
        assertThat(area.location().storageClass()).isEqualTo(StorageClass.OVERSIZE);
        assertThat(area.location().pickable()).isTrue();
        assertThat(errorOf(() -> area.update(details(AreaType.OVERFLOW), 3, "hn", AreaTest::newId)))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    @DisplayName("a status change is written to the area and its location alike (#18 D8)")
    void statusReachesTheLocation() {
        Area area = area("rcv02", AreaType.RECEIVING);

        area.changeStatus(LocationStatus.INACTIVE);

        assertThat(area.status()).isEqualTo(LocationStatus.INACTIVE);
        assertThat(area.location().status()).isEqualTo(LocationStatus.INACTIVE);
        assertThat(area.holdsPlace()).isFalse();
        assertThat(errorOf(() -> area.changeStatus(null))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}

package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.api.CapacityGroup;
import com.stockflow.warehouse.api.WarehouseCapacity;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.AreaRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.BinRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.ShelfRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.WarehouseRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.ZoneRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The map's half of the capacity board (kltn-docs 06 §4.6, BR-19; C40, C43): which bins offer room, which are counted
 * apart for lacking a clear height, and how they group into zones, the shelves of no zone, shelves and areas.
 */
class WarehouseCapacitiesImplTest {

    private static final UUID WAREHOUSE = UUID.fromString("0192f000-0000-7000-8000-0000000000c1");
    private static final UUID ZONE = UUID.fromString("0192f000-0000-7000-8000-0000000000c2");
    private static final UUID ZONED = UUID.fromString("0192f000-0000-7000-8000-0000000000c3");
    private static final UUID LOOSE = UUID.fromString("0192f000-0000-7000-8000-0000000000c4");
    private static final UUID RETIRED = UUID.fromString("0192f000-0000-7000-8000-0000000000c5");
    private static final UUID AREA = UUID.fromString("0192f000-0000-7000-8000-0000000000c6");
    private static final UUID GONE_AREA = UUID.fromString("0192f000-0000-7000-8000-0000000000c7");

    private static BinRow bin(String code, LocationStatus status, UUID shelf, String width, String length,
                              String height) {
        LocationStatus shelfStatus = shelf.equals(RETIRED) ? LocationStatus.INACTIVE
                : shelf.equals(LOOSE) ? LocationStatus.MAINTENANCE : LocationStatus.ACTIVE;
        return new BinRow(code, status, shelf, shelfStatus, new BigDecimal(width), new BigDecimal(length),
                height == null ? null : new BigDecimal(height));
    }

    private static WarehouseCapacity assemble(WarehouseStatus status) {
        return WarehouseCapacitiesImpl.assemble(
                new WarehouseRow(WAREHOUSE, "HN", "Kho Hà Nội", status, new BigDecimal("0.8500")),
                List.of(new ZoneRow(ZONE, "Khu A")),
                List.of(new ShelfRow(ZONED, "A01", "Kệ A01", ZONE, LocationStatus.ACTIVE),
                        new ShelfRow(LOOSE, "B01", "Kệ B01", null, LocationStatus.MAINTENANCE),
                        new ShelfRow(RETIRED, "C01", "Kệ C01", null, LocationStatus.INACTIVE)),
                List.of(bin("HN-A01-1-01", LocationStatus.ACTIVE, ZONED, "2", "1", "1.5"),        // 3 m³
                        bin("HN-A01-1-02", LocationStatus.BLOCKED, ZONED, "2", "1", "1.5"),       // blocked: none
                        bin("HN-A01-2-01", LocationStatus.ACTIVE, ZONED, "2", "1", null),         // C40: no height
                        bin("HN-A01-2-02", LocationStatus.INACTIVE, ZONED, "2", "1", null),       // off the layout
                        bin("HN-B01-1-01", LocationStatus.ACTIVE, LOOSE, "1", "1", "1"),          // shelf in maintenance
                        bin("HN-C01-1-01", LocationStatus.ACTIVE, RETIRED, "1", "1", "1")),       // shelf retired
                List.of(new AreaRow(AREA, "RCV01", "Khu nhận", LocationStatus.ACTIVE, "HN-RCV01"),
                        new AreaRow(GONE_AREA, "OLD01", "Khu cũ", LocationStatus.INACTIVE, "HN-OLD01")));
    }

    private static Map<String, CapacityGroup> byKey(WarehouseCapacity capacity) {
        return capacity.groups().stream().collect(Collectors.toMap(CapacityGroup::key, Function.identity()));
    }

    @Test
    @DisplayName("BR-19, C40: only usable bins with a clear height offer room; one without a height is counted apart")
    void usableBinsOnly() {
        WarehouseCapacity capacity = assemble(WarehouseStatus.ACTIVE);

        assertThat(capacity.active()).isTrue();
        assertThat(capacity.utilizationFactor()).isEqualByComparingTo("0.85");
        assertThat(capacity.binVolumeM3()).as("bin A01-1-01 only: blocked, unmeasured, maintenance, retired add none")
                .isEqualByComparingTo("3");
        assertThat(capacity.binsWithoutVolume()).as("A01-2-01; not A01-2-02, taken off the layout").isEqualTo(1);
    }

    @Test
    @DisplayName("06 §4.6 step 4: zones, the shelves of no zone, shelves and areas - nothing of the retired ones")
    void groups() {
        WarehouseCapacity capacity = assemble(WarehouseStatus.ACTIVE);

        assertThat(capacity.groups()).extracting(CapacityGroup::key).containsExactly("ZONE:" + ZONE, "NO_ZONE",
                "SHELF:" + ZONED, "SHELF:" + LOOSE, "AREA:" + AREA);
        Map<String, CapacityGroup> groups = byKey(capacity);
        assertThat(groups.get("ZONE:" + ZONE).binVolumeM3()).isEqualByComparingTo("3");
        assertThat(groups.get("ZONE:" + ZONE).binsWithoutVolume()).isEqualTo(1);
        assertThat(groups.get("ZONE:" + ZONE).name()).isEqualTo("Khu A");
        assertThat(groups.get("NO_ZONE").binVolumeM3()).as("shelf B01 is in maintenance").isEqualByComparingTo("0");
        assertThat(groups.get("NO_ZONE").id()).isNull();
        assertThat(groups.get("SHELF:" + ZONED).code()).isEqualTo("A01");
        assertThat(groups.get("AREA:" + AREA).binVolumeM3()).as("an area has no capacity (SCRUM-666)").isNull();
        assertThat(groups.get("AREA:" + AREA).kind()).isEqualTo(CapacityGroup.Kind.AREA);

        assertThat(capacity.groupsOfLocation().get("HN-A01-1-02")).as("a blocked bin still has its rows")
                .containsExactly("ZONE:" + ZONE, "SHELF:" + ZONED);
        assertThat(capacity.groupsOfLocation().get("HN-B01-1-01")).containsExactly("NO_ZONE", "SHELF:" + LOOSE);
        assertThat(capacity.groupsOfLocation().get("HN-RCV01")).containsExactly("AREA:" + AREA);
        assertThat(capacity.groupsOfLocation()).doesNotContainKeys("HN-C01-1-01", "HN-OLD01");
    }

    @Test
    @DisplayName("an inactive warehouse has no usable bin, so no capacity; its unmeasured bins are still counted")
    void inactiveWarehouse() {
        WarehouseCapacity capacity = assemble(WarehouseStatus.INACTIVE);

        assertThat(capacity.active()).isFalse();
        assertThat(capacity.binVolumeM3()).isEqualByComparingTo("0");
        assertThat(capacity.binsWithoutVolume()).isEqualTo(1);
    }
}

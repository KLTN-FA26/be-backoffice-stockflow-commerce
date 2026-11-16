package com.stockflow.order.internal.domain;

import com.stockflow.order.internal.domain.CapacityBoard.AreaKind;
import com.stockflow.order.internal.domain.CapacityBoard.AreaSpace;
import com.stockflow.order.internal.domain.CapacityBoard.BinSpace;
import com.stockflow.order.internal.domain.CapacityBoard.CartonSize;
import com.stockflow.order.internal.domain.CapacityBoard.Due;
import com.stockflow.order.internal.domain.CapacityBoard.Inputs;
import com.stockflow.order.internal.domain.CapacityBoard.Load;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** kltn-docs 06 §4.6 and the rules decided for SCRUM-669 (design 2026-11-16). */
class CapacityBoardTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID ZONE_A = UUID.randomUUID();

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static Load load(long cartons, String m3, String kg) {
        return new Load(cartons, d(m3), d(kg), 0);
    }

    private static BinSpace bin(String code, UUID zone, String shelf, String m3, String kg, boolean usable) {
        return new BinSpace(code, zone, zone == null ? null : "Zone A", shelf, m3 == null ? null : d(m3),
                kg == null ? null : d(kg), usable);
    }

    /** Two usable bins of 8 m³ / 500 kg on A01 in zone A, a blocked one, one on a shelf in no zone. */
    private static List<BinSpace> bins() {
        return List.of(bin("W-A01-1-A", ZONE_A, "A01", "8", "500", true),
                bin("W-A01-1-B", ZONE_A, "A01", "8", "500", true),
                bin("W-A01-2-A", ZONE_A, "A01", "8", "500", false),
                bin("W-B01-1-A", null, "B01", "4", null, true));
    }

    private static List<AreaSpace> areas() {
        return List.of(new AreaSpace("W-RCV01", "RCV01", "Receiving", AreaKind.RECEIVING),
                new AreaSpace("W-QC01", "QC01", "Quarantine", AreaKind.QUARANTINE),
                new AreaSpace("W-STG01", "STG01", "Staging", AreaKind.STAGING),
                new AreaSpace("W-DSP01", "DSP01", "Dispatch", AreaKind.DISPATCH));
    }

    private static CapacityBoard board(String factor, Map<String, Load> loads, List<Due> due,
                                       Map<String, CartonSize> sizes) {
        return CapacityBoard.compute(new Inputs(WAREHOUSE, "W", d(factor), bins(), areas(), loads,
                load(2, "0.5", "20"), due, sizes));
    }

    @Test
    @DisplayName("capacity is the usable bins' volume and load limit times the factor; a blocked bin adds none")
    void capacity() {
        CapacityBoard b = board("0.5", Map.of(), List.of(), Map.of());

        assertThat(b.capacityM3()).isEqualByComparingTo("10");      // (8 + 8 + 4) x 0.5
        assertThat(b.capacityKg()).isEqualByComparingTo("500");     // (500 + 500) x 0.5
        assertThat(b.binsWithoutWeightLimit()).isEqualTo(1);
        assertThat(b.binsWithoutVolume()).isZero();
    }

    @Test
    @DisplayName("occupied counts bins of any status and the receiving / quarantine areas, not staging or dispatch")
    void occupied() {
        CapacityBoard b = board("1", Map.of(
                "W-A01-1-A", load(3, "1", "30"),
                "W-A01-2-A", load(1, "0.5", "10"),                    // a blocked bin still holds it
                "W-RCV01", load(2, "1", "20"),
                "W-QC01", load(1, "0.25", "5"),
                "W-STG01", load(4, "2", "40"),
                "W-DSP01", load(5, "3", "50")), List.of(), Map.of());

        assertThat(b.occupied().cartons()).isEqualTo(7);
        assertThat(b.occupied().volumeM3()).isEqualByComparingTo("2.75");
        assertThat(b.areas()).filteredOn(a -> a.kind() == AreaKind.STAGING).singleElement()
                .satisfies(a -> {
                    assertThat(a.countsAsOccupied()).isFalse();
                    assertThat(a.occupied().cartons()).isEqualTo(4);
                });
        assertThat(b.areas()).filteredOn(a -> a.kind() == AreaKind.QUARANTINE).singleElement()
                .satisfies(a -> assertThat(a.countsAsOccupied()).isTrue());
    }

    @Test
    @DisplayName("incoming comes off expected free, pending only off free-if-all-approved; outbound is never added back")
    void freeSpace() {
        CapacityBoard b = board("1", Map.of("W-A01-1-A", load(2, "2", "20")),
                List.of(new Due("SKU-1", false, 4), new Due("SKU-1", true, 2)),
                Map.of("SKU-1", CartonSize.ofCentimetres(d("50"), d("50"), d("40"), d("8"))));   // 0.1 m³ a carton

        assertThat(b.capacityM3()).isEqualByComparingTo("20");
        assertThat(b.incoming().volumeM3()).isEqualByComparingTo("0.4");
        assertThat(b.pendingApproval().volumeM3()).isEqualByComparingTo("0.2");
        assertThat(b.expectedFreeM3()).isEqualByComparingTo("17.6");     // 20 - 2 - 0.4
        assertThat(b.freeIfAllApprovedM3()).isEqualByComparingTo("17.4");
        assertThat(b.outbound().cartons()).isEqualTo(2);
        assertThat(b.occupiedPercent()).isEqualByComparingTo("10.0");
        assertThat(b.expectedFreePercent()).isEqualByComparingTo("88.0");
        assertThat(b.freeKg()).isEqualByComparingTo("980");
    }

    @Test
    @DisplayName("free space may go negative")
    void negative() {
        CapacityBoard b = board("1", Map.of(), List.of(new Due("BIG", false, 300)),
                Map.of("BIG", new CartonSize(d("0.1"), null)));

        assertThat(b.expectedFreeM3()).isEqualByComparingTo("-10");
        assertThat(b.expectedFreePercent()).isEqualByComparingTo("-50.0");
    }

    @Test
    @DisplayName("a SKU with no carton size counts its cartons as unmeasured, at 0 m³")
    void unmeasured() {
        CapacityBoard b = board("1", Map.of(), List.of(new Due("NO-SPEC", false, 6), new Due("NO-DIMS", true, 2)),
                Map.of("NO-DIMS", CartonSize.ofCentimetres(null, d("10"), d("10"), d("1"))));

        assertThat(b.incoming().cartons()).isEqualTo(6);
        assertThat(b.incoming().volumeM3()).isEqualByComparingTo("0");
        assertThat(b.unmeasuredCartons()).isEqualTo(8);
    }

    @Test
    @DisplayName("no usable bin: capacity 0 and no percentage, not a division by zero")
    void noCapacity() {
        CapacityBoard b = CapacityBoard.compute(new Inputs(WAREHOUSE, "W", BigDecimal.ONE,
                List.of(bin("W-A01-1-A", ZONE_A, "A01", "8", "500", false), bin("W-X-1-A", null, "X", null, null, true)),
                List.of(), Map.of("W-A01-1-A", load(1, "1", "1")), Load.NONE, List.of(), Map.of()));

        assertThat(b.capacityM3()).isEqualByComparingTo("0");
        assertThat(b.occupiedPercent()).isNull();
        assertThat(b.expectedFreePercent()).isNull();
        assertThat(b.binsWithoutVolume()).isEqualTo(1);
    }

    @Test
    @DisplayName("zone and shelf rows use the factor and add up; shelves in no zone come in a last 'no zone' row")
    void sections() {
        CapacityBoard b = board("0.5", Map.of("W-A01-1-A", load(1, "1", "10"), "W-B01-1-A", load(1, "1", "10")),
                List.of(), Map.of());

        assertThat(b.zones()).hasSize(2);
        assertThat(b.zones().get(0).zoneId()).isEqualTo(ZONE_A);
        assertThat(b.zones().get(0).capacityM3()).isEqualByComparingTo("8");
        assertThat(b.zones().get(1).zoneId()).isNull();
        assertThat(b.zones().get(1).capacityM3()).isEqualByComparingTo("2");
        assertThat(b.zones().stream().map(CapacityBoard.Section::capacityM3).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(b.capacityM3());
        assertThat(b.shelves()).extracting(CapacityBoard.Section::shelfCode).containsExactly("A01", "B01");
        assertThat(b.shelves().get(1).freeM3()).isEqualByComparingTo("1");
    }
}

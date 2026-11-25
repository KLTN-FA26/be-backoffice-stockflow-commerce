package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.BinContents;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.SkuLot;
import com.stockflow.inventory.internal.service.BinSuggestionInputs.Reserved;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BinSuggestionInputs#occupancy}: what stands in a bin plus the space reserved there, reduced for a group of
 * cartons (issue #412). A bin is "the same SKU" / "the same lot" only when it holds all of the group, standing or
 * reserved; a reserved carton of a foreign SKU takes room and nothing else. The reads are mocked, the reduction
 * ({@link BinContents#toOccupancy}) is the real one.
 */
class BinSuggestionInputsTest {

    private static final SkuLot S1_L1 = new SkuLot("S1", "L1");
    private static final SkuLot S2_L2 = new SkuLot("S2", "L2");
    private static final Set<SkuLot> GROUP = Set.of(S1_L1, S2_L2);
    private static final List<UUID> LEAVE_OUT = List.of(UUID.randomUUID());

    private final BinOccupancyQuery query = mock(BinOccupancyQuery.class);
    private final BinSuggestionInputs inputs =
            new BinSuggestionInputs(mock(WarehouseService.class), mock(PutawayTrips.class), query);

    private static BinContents contents(int units, String volumeM3, String weightKg, Set<String> skus,
                                        Set<SkuLot> lots) {
        return new BinContents(units, new BigDecimal(volumeM3), new BigDecimal(weightKg), skus, lots);
    }

    private static Reserved reserved(String bin, String sku, String lot, int units, String volumeM3,
                                     String weightKg) {
        return new Reserved(bin, sku, lot, units, new BigDecimal(volumeM3), new BigDecimal(weightKg));
    }

    private Map<String, Occupancy> occupancy(Set<SkuLot> group, Map<String, BinContents> standing,
                                             List<Reserved> reserved) {
        when(query.contentsOf(any(), any(), any())).thenReturn(standing);
        return inputs.occupancy(List.of("A", "B", "C", "D", "E"), group, LEAVE_OUT, reserved);
    }

    @Test
    @DisplayName("a bin is the same SKU only holding every SKU of the group, the same lot only holding every pair")
    void theGroupIsHeldOnlyWhole() {
        Map<String, Occupancy> out = occupancy(GROUP, Map.of(
                "A", contents(1, "0.1", "2", Set.of("S1"), Set.of(S1_L1)),
                "B", contents(2, "0.2", "4", Set.of("S1", "S2"), Set.of(S1_L1)),
                "C", contents(2, "0.2", "4", Set.of("S1", "S2"), Set.of(S1_L1, S2_L2))), List.of());

        assertThat(out.get("A").holdsSku()).isFalse();
        assertThat(out.get("A").holdsLot()).isFalse();
        assertThat(out.get("B").holdsSku()).isTrue();
        assertThat(out.get("B").holdsLot()).isFalse();
        assertThat(out.get("C").holdsSku()).isTrue();
        assertThat(out.get("C").holdsLot()).isTrue();
    }

    @Test
    @DisplayName("a reserved carton of the group's missing pair completes the set, and its room is counted")
    void aReservedPairCompletesTheGroup() {
        // S2 stands in D in another lot: the contents name S2 as a SKU, and no pair of S2.
        Map<String, Occupancy> out = occupancy(GROUP,
                Map.of("D", contents(2, "0.20", "4", Set.of("S1", "S2"), Set.of(S1_L1))),
                List.of(reserved("D", "S2", "L2", 3, "0.06", "5")));

        Occupancy d = out.get("D");
        assertThat(d.holdsSku()).isTrue();
        assertThat(d.holdsLot()).isTrue();
        assertThat(d.units()).isEqualTo(5);
        assertThat(d.volumeM3()).isEqualByComparingTo("0.26");
        assertThat(d.weightKg()).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("a reserved carton of a foreign SKU takes room but makes the bin neither the same SKU nor lot")
    void aForeignReservationTakesRoomOnly() {
        Map<String, Occupancy> out = occupancy(GROUP,
                Map.of("A", contents(1, "0.1", "2", Set.of("S1"), Set.of(S1_L1))),
                List.of(reserved("A", "S9", "L9", 4, "0.3", "7"), reserved("B", "S9", "L9", 1, "0.05", "1")));

        Occupancy a = out.get("A");
        assertThat(a.holdsSku()).isFalse();
        assertThat(a.holdsLot()).isFalse();
        assertThat(a.units()).isEqualTo(5);
        assertThat(a.volumeM3()).isEqualByComparingTo("0.4");
        assertThat(a.weightKg()).isEqualByComparingTo("9");
        Occupancy b = out.get("B");
        assertThat(b.holdsSku()).isFalse();
        assertThat(b.holdsLot()).isFalse();
        assertThat(b.units()).isEqualTo(1);
    }

    @Test
    @DisplayName("a pair of the group without a lot is never the same lot, standing or reserved")
    void aNullLotIsNeverTheSameLot() {
        SkuLot noLot = new SkuLot("S3", null);
        Map<String, Occupancy> out = occupancy(Set.of(noLot),
                Map.of("A", contents(1, "0.1", "2", Set.of("S3"), Set.of(noLot))),
                List.of(reserved("A", "S3", null, 1, "0.1", "2"), reserved("B", "S3", null, 1, "0.1", "2")));

        assertThat(out.get("A").holdsSku()).isTrue();
        assertThat(out.get("A").holdsLot()).isFalse();
        assertThat(out.get("B").holdsSku()).isTrue();
        assertThat(out.get("B").holdsLot()).isFalse();
    }

    @Test
    @DisplayName("a bin holding nothing but reserved for a carton starts from empty")
    void aReservedOnlyBinStartsEmpty() {
        Map<String, Occupancy> out = occupancy(Set.of(S1_L1), Map.of(),
                List.of(reserved("E", "S1", "L1", 2, "0.06", "5")));

        assertThat(out).containsOnlyKeys("E");
        assertThat(out.get("E")).isEqualTo(new Occupancy(2, new BigDecimal("0.06"), new BigDecimal("5"), true, true));
    }
}

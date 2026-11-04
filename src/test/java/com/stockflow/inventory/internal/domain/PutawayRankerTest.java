package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.internal.domain.PutawayRanker.Bin;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.domain.PutawayRanker.Reason;
import com.stockflow.inventory.internal.domain.PutawayRanker.Suggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PutawayRankerTest {

    private static final Need TEN = new Need(10, new BigDecimal("0.5"));

    private static Occupancy held(int units) {
        return new Occupancy(units, BigDecimal.ZERO, false, false);
    }

    private static List<String> codes(List<Suggestion> s) {
        return s.stream().map(Suggestion::locationCode).toList();
    }

    @Test
    @DisplayName("BR-SLT-001: a bin without room for the whole need is never suggested")
    void areaAndFullBinsAreDropped() {
        Bin full = new Bin("FULL", 500, null);
        assertThat(PutawayRanker.rank(TEN, List.of(full), Map.of("FULL", held(495)))).isEmpty();
        assertThat(PutawayRanker.rank(TEN, List.of(full), Map.of("FULL", held(490)))).hasSize(1);
    }

    @Test
    @DisplayName("a bin with no recorded capacity is unlimited")
    void nullCapacityIsUnlimited() {
        List<Suggestion> out = PutawayRanker.rank(TEN, List.of(new Bin("A", null, null)), Map.of("A", held(100000)));
        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.freeUnits()).isNull();
            assertThat(s.freeWeightKg()).isNull();
        });
    }

    @Test
    @DisplayName("BR-SLT-003: weight is checked only when both the bin limit and the unit weight are known")
    void weightFilterOnlyWhenBothKnown() {
        Bin light = new Bin("L", null, new BigDecimal("10"));
        Map<String, Occupancy> occ = Map.of("L", new Occupancy(20, new BigDecimal("8"), false, false));
        assertThat(PutawayRanker.rank(TEN, List.of(light), occ)).isEmpty();                  // 8 + 5 > 10
        assertThat(PutawayRanker.rank(new Need(10, null), List.of(light), occ)).hasSize(1);  // unit weight unknown
        assertThat(PutawayRanker.rank(TEN, List.of(new Bin("L", null, null)), occ)).hasSize(1);
        assertThat(PutawayRanker.rank(new Need(4, new BigDecimal("0.5")), List.of(light), occ))
                .singleElement().satisfies(s -> assertThat(s.freeWeightKg()).isEqualByComparingTo("2"));
    }

    @Test
    @DisplayName("BR-SLT-004: same lot, then same SKU, then an empty bin, then any bin with room")
    void ranksSameLotThenSameSkuThenEmptyThenOther() {
        List<Bin> bins = List.of(new Bin("D", 1000, null), new Bin("C", 1000, null), new Bin("B", 1000, null),
                new Bin("A", 1000, null));
        Map<String, Occupancy> occ = Map.of(
                "A", new Occupancy(5, BigDecimal.ZERO, true, true),
                "B", new Occupancy(5, BigDecimal.ZERO, true, false),
                "D", held(5));
        List<Suggestion> out = PutawayRanker.rank(TEN, bins, occ);
        assertThat(codes(out)).containsExactly("A", "B", "C", "D");
        assertThat(out).extracting(Suggestion::reason)
                .containsExactly(Reason.SAME_LOT, Reason.SAME_SKU, Reason.EMPTY, Reason.HAS_ROOM);
    }

    @Test
    @DisplayName("within a reason, more free room first, unlimited before any number, then by code")
    void moreRoomFirstWithinARank() {
        List<Bin> bins = List.of(new Bin("B", 100, null), new Bin("A", 100, null), new Bin("C", 300, null),
                new Bin("Z", null, null));
        assertThat(codes(PutawayRanker.rank(TEN, bins, Map.of()))).containsExactly("Z", "C", "A", "B");
    }

    @Test
    void atMostTen() {
        List<Bin> bins = new ArrayList<>();
        for (int i = 0; i < 15; i++) bins.add(new Bin(String.format("B%02d", i), null, null));
        assertThat(PutawayRanker.rank(TEN, bins, Map.of())).hasSize(PutawayRanker.MAX_SUGGESTIONS).hasSize(10);
    }

    @Test
    void fitsMatchesTheFilter() {
        Bin bin = new Bin("X", 100, new BigDecimal("50"));
        for (int units : new int[] {0, 80, 90, 91}) {
            for (String w : new String[] {"0", "44", "45", "46"}) {
                Occupancy occ = new Occupancy(units, new BigDecimal(w), false, false);
                assertThat(PutawayRanker.fits(TEN, bin, occ))
                        .isEqualTo(!PutawayRanker.rank(TEN, List.of(bin), Map.of("X", occ)).isEmpty());
            }
        }
        assertThat(PutawayRanker.fits(TEN, bin, new Occupancy(90, new BigDecimal("45"), false, false))).isTrue();
        assertThat(PutawayRanker.fits(TEN, bin, new Occupancy(91, BigDecimal.ZERO, false, false))).isFalse();
        assertThat(PutawayRanker.fits(TEN, bin, new Occupancy(0, new BigDecimal("46"), false, false))).isFalse();
    }
}

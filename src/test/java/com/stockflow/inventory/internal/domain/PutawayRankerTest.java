package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.internal.domain.CartonFit.Carton;
import com.stockflow.inventory.internal.domain.CartonFit.Space;
import com.stockflow.inventory.internal.domain.PutawayRanker.Bin;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.domain.PutawayRanker.Reason;
import com.stockflow.inventory.internal.domain.PutawayRanker.Refusal;
import com.stockflow.inventory.internal.domain.PutawayRanker.Suggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 05 BR-03 / BR-04 and 06 §4.4 (team plan 5-4): a bin is suggested when the carton fits it dimension by
 * dimension and its free volume and load take the carton; capacity is volume, never a count of units.
 *
 * <p>The carton: 50 x 40 x 30 cm = 0.06 m³, 5 kg full. A "1 m³" bin is 1 m x 1 m x 1 m.</p>
 */
class PutawayRankerTest {

    private static final Need CARTON = new Need(new Carton(new BigDecimal("50"), new BigDecimal("40"),
            new BigDecimal("30"), true), new BigDecimal("5"));
    private static final Space CUBE = new Space(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

    private static Bin bin(String code, String capacityM3) {
        return new Bin(code, CUBE, capacityM3 == null ? null : new BigDecimal(capacityM3), null);
    }

    private static Occupancy holding(String volumeM3) {
        return new Occupancy(1, new BigDecimal(volumeM3), BigDecimal.ZERO, false, false);
    }

    private static List<String> codes(List<Suggestion> s) {
        return s.stream().map(Suggestion::locationCode).toList();
    }

    @Test
    @DisplayName("BR-03: a bin without the volume for the whole carton is never suggested")
    void fullBinsAreDropped() {
        Bin bin = bin("B", "1");
        assertThat(PutawayRanker.rank(CARTON, List.of(bin), Map.of("B", holding("0.941")))).isEmpty();
        assertThat(PutawayRanker.rank(CARTON, List.of(bin), Map.of("B", holding("0.94")))).hasSize(1);
    }

    @Test
    @DisplayName("06 BR-01: a bin the carton does not fit is never suggested, however empty")
    void cartonMustFit() {
        Bin narrow = new Bin("N", new Space(new BigDecimal("0.35"), new BigDecimal("0.35"), BigDecimal.ONE),
                new BigDecimal("1"), null);
        assertThat(PutawayRanker.rank(CARTON, List.of(narrow), Map.of())).isEmpty();
        assertThat(PutawayRanker.refusal(CARTON, narrow, Occupancy.EMPTY)).isEqualTo(Refusal.DOES_NOT_FIT);
    }

    @Test
    @DisplayName("a bin without a volume (its level has no clear height) is unlimited")
    void nullCapacityIsUnlimited() {
        List<Suggestion> out = PutawayRanker.rank(CARTON, List.of(bin("A", null)), Map.of("A", holding("1000")));
        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.freeVolumeM3()).isNull();
            assertThat(s.freeWeightKg()).isNull();
        });
    }

    @Test
    @DisplayName("BR-03: weight is checked only when both the bin limit and the carton's weight are known")
    void weightFilterOnlyWhenBothKnown() {
        Bin light = new Bin("L", CUBE, null, new BigDecimal("10"));
        Map<String, Occupancy> occ = Map.of("L", new Occupancy(20, BigDecimal.ZERO, new BigDecimal("8"), false, false));
        assertThat(PutawayRanker.rank(CARTON, List.of(light), occ)).isEmpty();                  // 8 + 5 > 10
        assertThat(PutawayRanker.rank(new Need(CARTON.carton(), null), List.of(light), occ)).hasSize(1);
        assertThat(PutawayRanker.rank(CARTON, List.of(bin("L", null)), occ)).hasSize(1);
        assertThat(PutawayRanker.rank(new Need(CARTON.carton(), new BigDecimal("2")), List.of(light), occ))
                .singleElement().satisfies(s -> assertThat(s.freeWeightKg()).isEqualByComparingTo("2"));
    }

    @Test
    @DisplayName("BR-SLT-004: same lot, then same SKU, then an empty bin, then any bin with room")
    void ranksSameLotThenSameSkuThenEmptyThenOther() {
        List<Bin> bins = List.of(bin("D", "1"), bin("C", "1"), bin("B", "1"), bin("A", "1"));
        Map<String, Occupancy> occ = Map.of(
                "A", new Occupancy(5, new BigDecimal("0.1"), BigDecimal.ZERO, true, true),
                "B", new Occupancy(5, new BigDecimal("0.1"), BigDecimal.ZERO, true, false),
                "D", holding("0.1"));
        List<Suggestion> out = PutawayRanker.rank(CARTON, bins, occ);
        assertThat(codes(out)).containsExactly("A", "B", "C", "D");
        assertThat(out).extracting(Suggestion::reason)
                .containsExactly(Reason.SAME_LOT, Reason.SAME_SKU, Reason.EMPTY, Reason.HAS_ROOM);
    }

    @Test
    @DisplayName("within a reason, more free volume first, then by code; a bin without a volume after every measured one")
    void moreRoomFirstWithinARank() {
        List<Bin> bins = List.of(bin("B", "1"), bin("A", "1"), bin("C", "3"), bin("Z", null));
        assertThat(codes(PutawayRanker.rank(CARTON, bins, Map.of()))).containsExactly("C", "A", "B", "Z");
    }

    @Test
    void atMostTen() {
        List<Bin> bins = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            bins.add(bin(String.format("B%02d", i), null));
        }
        assertThat(PutawayRanker.rank(CARTON, bins, Map.of())).hasSize(PutawayRanker.MAX_SUGGESTIONS).hasSize(10);
    }

    @Test
    @DisplayName("fits says what the filter says, and refusal names size before room")
    void fitsMatchesTheFilter() {
        Bin bin = new Bin("X", CUBE, BigDecimal.ONE, new BigDecimal("50"));
        for (String volume : new String[] {"0", "0.93", "0.94", "0.941"}) {
            for (String weight : new String[] {"0", "44", "45", "46"}) {
                Occupancy occ = new Occupancy(1, new BigDecimal(volume), new BigDecimal(weight), false, false);
                assertThat(PutawayRanker.fits(CARTON, bin, occ))
                        .isEqualTo(!PutawayRanker.rank(CARTON, List.of(bin), Map.of("X", occ)).isEmpty());
            }
        }
        assertThat(PutawayRanker.refusal(CARTON, bin,
                new Occupancy(1, new BigDecimal("0.94"), new BigDecimal("45"), false, false))).isNull();
        assertThat(PutawayRanker.refusal(CARTON, bin, holding("0.941"))).isEqualTo(Refusal.NO_ROOM);
        assertThat(PutawayRanker.refusal(CARTON, bin,
                new Occupancy(1, BigDecimal.ZERO, new BigDecimal("46"), false, false))).isEqualTo(Refusal.NO_ROOM);
        Bin tooLow = new Bin("T", new Space(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.29")), BigDecimal.ONE,
                null);
        assertThat(PutawayRanker.refusal(CARTON, tooLow, holding("0.999"))).isEqualTo(Refusal.DOES_NOT_FIT);
    }

    @Test
    @DisplayName("a carton nobody measured takes no volume and is not judged by size")
    void unmeasuredCarton() {
        assertThat(PutawayRanker.fits(Need.UNMEASURED, bin("B", "1"), holding("1"))).isTrue();
    }
}

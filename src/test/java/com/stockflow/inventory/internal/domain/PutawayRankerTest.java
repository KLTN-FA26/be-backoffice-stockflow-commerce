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

    private static Occupancy sameLot(String volumeM3) {
        return new Occupancy(5, new BigDecimal(volumeM3), BigDecimal.ZERO, true, true);
    }

    private static Occupancy sameSku(String volumeM3) {
        return new Occupancy(5, new BigDecimal(volumeM3), BigDecimal.ZERO, true, false);
    }

    @Test
    @DisplayName("05 §4.2 (2026-11-08): same lot, then an empty bin, then the same SKU of another lot, then other SKUs")
    void ranksSameLotThenEmptyThenSameSkuThenOther() {
        List<Bin> bins = List.of(bin("D", "1"), bin("C", "1"), bin("B", "1"), bin("A", "1"));
        Map<String, Occupancy> occ = Map.of("A", sameLot("0.1"), "C", sameSku("0.1"), "D", holding("0.1"));
        List<Suggestion> out = PutawayRanker.rank(CARTON, bins, occ);
        assertThat(codes(out)).containsExactly("A", "B", "C", "D");
        assertThat(out).extracting(Suggestion::reason)
                .containsExactly(Reason.SAME_LOT, Reason.EMPTY, Reason.SAME_SKU, Reason.OTHER_SKU);
    }

    @Test
    @DisplayName("same lot, same SKU, other SKU: the bin with less free volume first (best fit), then by code")
    void bestFitWithinTheOccupiedGroups() {
        List<Bin> bins = List.of(bin("L1", "1"), bin("L2", "1"), bin("S1", "1"), bin("S2", "1"), bin("O1", "1"),
                bin("O2", "1"), bin("O3", "1"));
        Map<String, Occupancy> occ = Map.of("L1", sameLot("0.2"), "L2", sameLot("0.7"), "S1", sameSku("0.1"),
                "S2", sameSku("0.5"), "O1", holding("0.3"), "O2", holding("0.6"), "O3", holding("0.6"));
        assertThat(codes(PutawayRanker.rank(CARTON, bins, occ)))
                .containsExactly("L2", "L1", "S2", "S1", "O2", "O3", "O1");
    }

    @Test
    @DisplayName("empty bins: the smallest that takes the whole rest of the lot first, then those that do not, larger first")
    void emptyBinsByTheRestOfTheLot() {
        // The docs' example: the lot is 3 cartons of 0.1 m³; empty bins of 0.8 and 1.5 take it, 0.25 and 0.2 do not.
        List<Bin> bins = List.of(bin("B04", "1.5"), bin("B05", "0.2"), bin("B03", "0.8"), bin("B06", "0.25"));
        PutawayRanker.Lot lot = new PutawayRanker.Lot(new BigDecimal("0.3"), new BigDecimal("15"));
        assertThat(codes(PutawayRanker.rank(CARTON, lot, bins, Map.of())))
                .containsExactly("B03", "B04", "B06", "B05");
    }

    @Test
    @DisplayName("an empty bin that takes the lot's volume but not its weight does not take the lot")
    void theLotsWeightCounts() {
        Bin light = new Bin("LIGHT", CUBE, new BigDecimal("0.5"), new BigDecimal("10"));
        Bin strong = new Bin("STRONG", CUBE, new BigDecimal("0.9"), new BigDecimal("100"));
        PutawayRanker.Lot lot = new PutawayRanker.Lot(new BigDecimal("0.3"), new BigDecimal("15"));
        assertThat(codes(PutawayRanker.rank(CARTON, lot, List.of(light, strong), Map.of())))
                .containsExactly("STRONG", "LIGHT");
    }

    @Test
    @DisplayName("06 §4.4: a bin without a volume ranks after EVERY measured bin, whatever its group; among them group, then code")
    void unmeasuredAfterEveryMeasuredBin() {
        List<Bin> bins = List.of(bin("U2", null), bin("U1", null), bin("U3", null), bin("M1", "1"), bin("M2", "1"));
        Map<String, Occupancy> occ = Map.of("U1", sameLot("5"), "U3", sameLot("1"), "M1", holding("0.1"),
                "M2", sameSku("0.2"));
        List<Suggestion> out = PutawayRanker.rank(CARTON, bins, occ);
        assertThat(codes(out)).containsExactly("M2", "M1", "U1", "U3", "U2");
        assertThat(out.get(2).reason()).isEqualTo(Reason.SAME_LOT);
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

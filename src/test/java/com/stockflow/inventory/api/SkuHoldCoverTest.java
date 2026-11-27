package com.stockflow.inventory.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SkuHoldCover: the shortage is per carton count (ADR-0011)")
class SkuHoldCoverTest {

    private static final Instant AT = Instant.parse("2026-11-26T03:00:00Z");

    private static SkuHoldCover.Hold hold(int cartons, int count) {
        return new SkuHoldCover.Hold(UUID.randomUUID(), UUID.randomUUID(), cartons * count, count, AT);
    }

    @Test
    @DisplayName("a count short of cartons is named with its shortfall; a covered count is absent")
    void shortCountOnly() {
        // 2 cartons of 1000 held, 2 free: covered. 3 cartons of 500 held, 2 free: one short.
        SkuHoldCover cover = new SkuHoldCover("CUP", "HCM", 3000, Map.of(1000, 2, 500, 2),
                List.of(hold(2, 1000), hold(3, 500)));

        assertThat(cover.uncoveredCartonsByCount()).containsExactly(Map.entry(500, 1));
        assertThat(cover.uncoveredUnits()).isEqualTo(500);
    }

    @Test
    @DisplayName("free cartons of another count make no shortage up")
    void otherCountCoversNothing() {
        SkuHoldCover cover = new SkuHoldCover("CUP", "HCM", 0, Map.of(1000, 5), List.of(hold(2, 500)));

        assertThat(cover.uncoveredCartonsByCount()).containsExactly(Map.entry(500, 2));
        assertThat(cover.uncoveredUnits()).isEqualTo(1000);
    }

    @Test
    @DisplayName("several counts short: each is named, in count order")
    void severalCountsShort() {
        SkuHoldCover cover = new SkuHoldCover("CUP", "HCM", 0, Map.of(500, 1),
                List.of(hold(1, 1000), hold(2, 500), hold(1, 500)));

        assertThat(cover.uncoveredCartonsByCount()).containsExactly(Map.entry(500, 2), Map.entry(1000, 1));
        assertThat(cover.uncoveredUnits()).isEqualTo(2000);
    }

    @Test
    @DisplayName("every hold covered: nothing short")
    void covered() {
        SkuHoldCover cover = new SkuHoldCover("CUP", "HCM", 2500, Map.of(1000, 2, 500, 1), List.of(hold(2, 1000)));

        assertThat(cover.uncoveredCartonsByCount()).isEmpty();
        assertThat(cover.uncoveredUnits()).isZero();
    }
}

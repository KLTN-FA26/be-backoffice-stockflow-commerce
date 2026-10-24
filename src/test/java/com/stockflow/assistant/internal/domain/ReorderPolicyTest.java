package com.stockflow.assistant.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design §8: the reorder arithmetic, in Java, with every input returned beside the result. */
class ReorderPolicyTest {

    /** Sales and stock only: no lead time, nothing on order or incoming, no cap, no supplier terms. */
    private static ReorderPolicy.Suggestion plain(long sold, int period, int cover, Integer safety, long atp) {
        return ReorderPolicy.suggest(new ReorderPolicy.Inputs(sold, period, cover, 0, safety, null, atp, 0, 0, null,
                null));
    }

    @Test
    @DisplayName("need = average daily sales × cover days + safety stock − available to promise, rounded up")
    void need() {
        // 300 sold in 30 days = 10/day; 30 days of cover = 300; + safety 50 − 120 available = 230.
        ReorderPolicy.Suggestion suggestion = plain(300, 30, 30, 50, 120);

        assertThat(suggestion.averageDailySales()).isEqualByComparingTo("10.00");
        assertThat(suggestion.target()).isEqualTo(350);
        assertThat(suggestion.position()).isEqualTo(120);
        assertThat(suggestion.need()).isEqualTo(230);
        assertThat(suggestion.quantity()).isEqualTo(230);
        assertThat(suggestion.roundedFor()).isNull();
    }

    @Test
    @DisplayName("a fractional need rounds up: a third of a unit still needs a unit")
    void roundsUp() {
        // 10 sold in 30 days = 0.33/day; × 7 = 2.31 → 3.
        assertThat(plain(10, 30, 7, null, 0).quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("the lead time is part of the horizon: the shelf empties while the order is on its way")
    void leadTime() {
        // 1,000/day, 21 days lead + 30 cover = 51,000, + 5,000 safety − 20,000 available = 36,000 (not 15,000).
        ReorderPolicy.Suggestion suggestion = ReorderPolicy.suggest(new ReorderPolicy.Inputs(30_000, 30, 30, 21,
                5_000, null, 20_000, 0, 0, null, null));

        assertThat(suggestion.quantity()).isEqualTo(36_000);
    }

    @Test
    @DisplayName("what is already bought, in receiving or on a transfer is not proposed again")
    void positionCountsWhatIsComing() {
        // Target 30,000 + 5,000 = 35,000; position 8,000 available + 30,000 on order + 10,000 incoming = 48,000.
        ReorderPolicy.Suggestion covered = ReorderPolicy.suggest(new ReorderPolicy.Inputs(30_000, 30, 30, 0,
                5_000, null, 8_000, 30_000, 10_000, null, null));
        assertThat(covered.position()).isEqualTo(48_000);
        assertThat(covered.quantity()).isZero();

        // Without the purchase order the same SKU would need 17,000.
        assertThat(ReorderPolicy.suggest(new ReorderPolicy.Inputs(30_000, 30, 30, 0, 5_000, null, 8_000, 0,
                10_000, null, null)).quantity()).isEqualTo(17_000);
    }

    @Test
    @DisplayName("the target never exceeds the SKU's maximum stock")
    void cappedAtMax() {
        // Sales say 35,000; the order-up-to level is 20,000; 8,000 available → 12,000.
        ReorderPolicy.Suggestion capped = ReorderPolicy.suggest(new ReorderPolicy.Inputs(30_000, 30, 30, 0, 5_000,
                20_000, 8_000, 0, 0, null, null));

        assertThat(capped.cappedAtMax()).isTrue();
        assertThat(capped.target()).isEqualTo(20_000);
        assertThat(capped.quantity()).isEqualTo(12_000);
    }

    @Test
    @DisplayName("at least the MOQ, then a multiple of the pack, even when the MOQ itself is not one")
    void packAndMoq() {
        ReorderPolicy.Suggestion packed = ReorderPolicy.suggest(new ReorderPolicy.Inputs(300, 30, 30, 0, 50, null,
                120, 0, 0, null, 100));
        assertThat(packed.quantity()).isEqualTo(300);
        assertThat(packed.roundedFor()).isEqualTo("PACK_SIZE");

        ReorderPolicy.Suggestion moq = ReorderPolicy.suggest(new ReorderPolicy.Inputs(30, 30, 10, 0, null, null, 0,
                0, 0, 500, 50));
        assertThat(moq.quantity()).isEqualTo(500);
        assertThat(moq.roundedFor()).isEqualTo("MOQ");

        // A legacy MOQ of 450 with packs of 100: 450 would be refused by the PO form, 500 is not.
        ReorderPolicy.Suggestion both = ReorderPolicy.suggest(new ReorderPolicy.Inputs(30, 30, 10, 0, null, null, 0,
                0, 0, 450, 100));
        assertThat(both.quantity()).isEqualTo(500);
        assertThat(both.roundedFor()).isEqualTo("MOQ_AND_PACK_SIZE");
    }

    @Test
    @DisplayName("enough stock means zero, and zero is never rounded up to a pack or an MOQ")
    void enoughStock() {
        ReorderPolicy.Suggestion none = ReorderPolicy.suggest(new ReorderPolicy.Inputs(30, 30, 30, 0, 10, null, 500,
                0, 0, 1000, 100));
        assertThat(none.quantity()).isZero();
        assertThat(none.need()).isZero();
        assertThat(none.roundedFor()).isNull();
    }

    @Test
    @DisplayName("periods are at least one day; sales, lead time and quantities coming are never negative")
    void validation() {
        assertThatThrownBy(() -> plain(1, 0, 7, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plain(-1, 7, 7, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReorderPolicy.Inputs(1, 7, 7, -1, null, null, 0, 0, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReorderPolicy.Inputs(1, 7, 7, 0, null, null, 0, -5, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

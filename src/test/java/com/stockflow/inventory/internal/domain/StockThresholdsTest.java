package com.stockflow.inventory.internal.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The definition of "low stock": at or below the reorder point, strictly below the safety stock, NULL is not 0. */
class StockThresholdsTest {

    @Test
    void reorderIsRequiredAtAndBelowThePointButNotAbove() {
        assertThat(StockThresholds.reorderRequired(9, 10)).isTrue();
        assertThat(StockThresholds.reorderRequired(10, 10)).isTrue();
        assertThat(StockThresholds.reorderRequired(11, 10)).isFalse();
    }

    @Test
    void safetyStockIsBreachedOnlyStrictlyBelow() {
        assertThat(StockThresholds.belowSafetyStock(4, 5)).isTrue();
        assertThat(StockThresholds.belowSafetyStock(5, 5)).isFalse();
        assertThat(StockThresholds.belowSafetyStock(6, 5)).isFalse();
    }

    @Test
    void aThresholdThatIsNotSetAnswersNullNotFalseAndNotZero() {
        assertThat(StockThresholds.reorderRequired(0, null)).isNull();
        assertThat(StockThresholds.belowSafetyStock(0, null)).isNull();
        // a real zero reorder point is a threshold: nothing left means reorder
        assertThat(StockThresholds.reorderRequired(0, 0)).isTrue();
        assertThat(StockThresholds.reorderRequired(1, 0)).isFalse();
        assertThat(StockThresholds.belowSafetyStock(0, 0)).isFalse();
    }

    @Test
    void aSkuIsLowWhenEitherThresholdIsCrossed() {
        assertThat(StockThresholds.isLow(10, 10, 5)).isTrue();   // at the reorder point
        assertThat(StockThresholds.isLow(11, 10, 12)).isTrue();  // only below the safety stock
        assertThat(StockThresholds.isLow(11, 10, 5)).isFalse();
        assertThat(StockThresholds.isLow(0, null, null)).isFalse();
        assertThat(StockThresholds.isLow(0, null, 1)).isTrue();
    }
}

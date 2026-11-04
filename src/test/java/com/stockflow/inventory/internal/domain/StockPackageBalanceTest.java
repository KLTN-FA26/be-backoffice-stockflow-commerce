package com.stockflow.inventory.internal.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-473: a location line's total is its three unit columns, never a figure of its own. */
class StockPackageBalanceTest {

    @Test
    void aLocationLinesTotalIsItsPartsAdded() {
        var line = new StockPackageBalance.LocationLine("SKU-1", null, null, StockStatus.AVAILABLE, 1, 1000, 1, 880, 20);

        assertThat(line.totalUnits()).isEqualTo(1900);
    }

    @Test
    void aTotalThatIsNotItsPartsIsRefused() {
        assertThatThrownBy(() -> new StockPackageBalance.LocationLine("SKU-1", null, null, StockStatus.AVAILABLE,
                1, 1000, 1, 880, 20, 2000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

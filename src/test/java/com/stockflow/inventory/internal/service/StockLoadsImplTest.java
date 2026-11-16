package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.StockLoads.Load;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** A SKU hold names units, not cartons: the capacity board rounds it up to whole cartons of the SKU's spec. */
class StockLoadsImplTest {

    private static CartonSpec spec(int perCarton, String lengthCm) {
        return new CartonSpec("SKU", "CTN", perCarton, lengthCm == null ? null : new BigDecimal(lengthCm),
                new BigDecimal("50"), new BigDecimal("40"), new BigDecimal("6"), false);
    }

    @Test
    @DisplayName("units not a multiple of the carton count round up to whole cartons")
    void roundsUp() {
        Load load = StockLoadsImpl.cartonsOf(25, spec(10, "50"));

        assertThat(load.cartons()).isEqualTo(3);
        assertThat(load.volumeM3()).isEqualByComparingTo("0.3");     // 3 x 0.5 x 0.5 x 0.4
        assertThat(load.weightKg()).isEqualByComparingTo("18");
        assertThat(load.unmeasuredCartons()).isZero();
    }

    @Test
    @DisplayName("a spec without a full size gives 0 m³ and counts its cartons as unmeasured; no spec at all, one")
    void unmeasured() {
        assertThat(StockLoadsImpl.cartonsOf(20, spec(10, null)))
                .satisfies(l -> {
                    assertThat(l.cartons()).isEqualTo(2);
                    assertThat(l.volumeM3()).isEqualByComparingTo("0");
                    assertThat(l.unmeasuredCartons()).isEqualTo(2);
                });
        assertThat(StockLoadsImpl.cartonsOf(20, null).unmeasuredCartons()).isEqualTo(1);
    }
}

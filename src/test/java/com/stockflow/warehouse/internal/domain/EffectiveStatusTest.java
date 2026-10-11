package com.stockflow.warehouse.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #18 D2: what a place's status is once everything above it is taken into account. */
class EffectiveStatusTest {

    @Test
    @DisplayName("a bin takes the narrowest of its warehouse, its shelf and its own status")
    void binIsTheNarrowest() {
        assertThat(EffectiveStatus.ofBin(WarehouseStatus.ACTIVE, LocationStatus.ACTIVE, LocationStatus.ACTIVE))
                .isEqualTo(LocationStatus.ACTIVE);
        assertThat(EffectiveStatus.ofBin(WarehouseStatus.ACTIVE, LocationStatus.MAINTENANCE, LocationStatus.ACTIVE))
                .isEqualTo(LocationStatus.MAINTENANCE);
        assertThat(EffectiveStatus.ofBin(WarehouseStatus.ACTIVE, LocationStatus.BLOCKED, LocationStatus.MAINTENANCE))
                .isEqualTo(LocationStatus.MAINTENANCE);
        assertThat(EffectiveStatus.ofBin(WarehouseStatus.ACTIVE, LocationStatus.MAINTENANCE, LocationStatus.INACTIVE))
                .isEqualTo(LocationStatus.INACTIVE);
        assertThat(EffectiveStatus.ofBin(WarehouseStatus.INACTIVE, LocationStatus.ACTIVE, LocationStatus.BLOCKED))
                .isEqualTo(LocationStatus.INACTIVE);
    }

    @Test
    @DisplayName("a shelf and an area are narrowed by their warehouse only")
    void shelfAndArea() {
        assertThat(EffectiveStatus.ofShelf(WarehouseStatus.ACTIVE, LocationStatus.BLOCKED))
                .isEqualTo(LocationStatus.BLOCKED);
        assertThat(EffectiveStatus.ofShelf(WarehouseStatus.INACTIVE, LocationStatus.ACTIVE))
                .isEqualTo(LocationStatus.INACTIVE);
        assertThat(EffectiveStatus.ofArea(WarehouseStatus.ACTIVE, LocationStatus.ACTIVE))
                .isEqualTo(LocationStatus.ACTIVE);
        assertThat(EffectiveStatus.ofArea(WarehouseStatus.INACTIVE, LocationStatus.MAINTENANCE))
                .isEqualTo(LocationStatus.INACTIVE);
    }

    @Test
    @DisplayName("only ACTIVE is usable")
    void usable() {
        assertThat(EffectiveStatus.isUsable(LocationStatus.ACTIVE)).isTrue();
        assertThat(EffectiveStatus.isUsable(LocationStatus.BLOCKED)).isFalse();
        assertThat(EffectiveStatus.isUsable(LocationStatus.MAINTENANCE)).isFalse();
        assertThat(EffectiveStatus.isUsable(LocationStatus.INACTIVE)).isFalse();
    }
}

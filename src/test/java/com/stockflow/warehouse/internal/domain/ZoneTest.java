package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZoneTest {

    private static final UUID ID = UUID.fromString("0190a000-0000-7000-8000-000000000002");
    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");

    @Test
    @DisplayName("trims the name and upper-cases the colour - the database accepts only #RRGGBB in capitals")
    void normalises() {
        Zone zone = Zone.create(ID, WAREHOUSE, "  Khu A - Sofa ", "#22c55e");

        assertThat(zone.name()).isEqualTo("Khu A - Sofa");
        assertThat(zone.color()).isEqualTo("#22C55E");
        assertThat(zone.warehouseId()).isEqualTo(WAREHOUSE);
    }

    @Test
    @DisplayName("a zone may have no colour")
    void colourIsOptional() {
        assertThat(Zone.create(ID, WAREHOUSE, "Khu A", null).color()).isNull();
        assertThat(Zone.create(ID, WAREHOUSE, "Khu A", " ").color()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"22C55E", "#22C55", "#22C55EE", "#GGGGGG", "red"})
    @DisplayName("refuses anything but #RRGGBB")
    void refusesBadColour(String color) {
        assertThatThrownBy(() -> Zone.create(ID, WAREHOUSE, "Khu A", color))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("refuses a blank name or one longer than the column")
    void refusesBadName() {
        assertThatThrownBy(() -> Zone.create(ID, WAREHOUSE, " ", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Zone.create(ID, WAREHOUSE, "x".repeat(101), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("renames and recolours when the caller saw the current version, never otherwise")
    void updates() {
        Zone zone = Zone.create(ID, WAREHOUSE, "Khu A", null);

        zone.update("Khu B", "#9BBB59", 0L);
        assertThat(zone.name()).isEqualTo("Khu B");
        assertThat(zone.color()).isEqualTo("#9BBB59");

        assertThatThrownBy(() -> zone.update("Khu C", null, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }
}

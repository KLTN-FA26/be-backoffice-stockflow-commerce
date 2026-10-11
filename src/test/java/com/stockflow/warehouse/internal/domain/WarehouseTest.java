package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WarehouseTest {

    private static final UUID ID = UUID.fromString("0190a000-0000-7000-8000-000000000001");

    private static Warehouse hanoi() {
        return Warehouse.register(ID, "hn", "Kho Hà Nội", "1 Phố Huế", null, MapUnit.M,
                new BigDecimal("40"), new BigDecimal("25"));
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Nested
    @DisplayName("registering")
    class Registering {

        @Test
        @DisplayName("starts ACTIVE with an upper-cased prefix")
        void startsActive() {
            Warehouse warehouse = hanoi();

            assertThat(warehouse.status()).isEqualTo(WarehouseStatus.ACTIVE);
            assertThat(warehouse.prefix()).isEqualTo("HN");
            assertThat(warehouse.mapWidth()).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("refuses a prefix that could not be part of a location code")
        void refusesBadPrefix() {
            assertThat(errorOf(() -> Warehouse.register(ID, "H-N", "Kho", "1 Phố Huế", null,
                    MapUnit.M, BigDecimal.TEN, BigDecimal.TEN))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> Warehouse.register(ID, "ABCDEFGHIJK", "Kho", "1 Phố Huế", null,
                    MapUnit.M, BigDecimal.TEN, BigDecimal.TEN))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("refuses a blank name or address")
        void refusesBlankText() {
            assertThat(errorOf(() -> Warehouse.register(ID, "HN", " ", "1 Phố Huế", null,
                    MapUnit.M, BigDecimal.TEN, BigDecimal.TEN))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> Warehouse.register(ID, "HN", "Kho", "", null,
                    MapUnit.M, BigDecimal.TEN, BigDecimal.TEN))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("refuses a map without a positive size or a unit")
        void refusesBadMap() {
            assertThat(errorOf(() -> Warehouse.register(ID, "HN", "Kho", "1 Phố Huế", null,
                    MapUnit.M, BigDecimal.ZERO, BigDecimal.TEN))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> Warehouse.register(ID, "HN", "Kho", "1 Phố Huế", null,
                    null, BigDecimal.TEN, BigDecimal.TEN))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("treats a blank return address as none")
        void blankReturnAddressIsNone() {
            Warehouse warehouse = Warehouse.register(ID, "HN", "Kho", "1 Phố Huế", "  ", MapUnit.M,
                    BigDecimal.TEN, BigDecimal.TEN);

            assertThat(warehouse.returnAddress()).isNull();
        }
    }

    @Nested
    @DisplayName("editing")
    class Editing {

        @Test
        @DisplayName("updates the details when the caller saw the current version")
        void updatesDetails() {
            Warehouse warehouse = hanoi();

            warehouse.updateDetails("Kho Hà Nội 2", "2 Phố Huế", "Kho trả hàng", 0L);

            assertThat(warehouse.name()).isEqualTo("Kho Hà Nội 2");
            assertThat(warehouse.address()).isEqualTo("2 Phố Huế");
            assertThat(warehouse.returnAddress()).isEqualTo("Kho trả hàng");
        }

        @Test
        @DisplayName("refuses an edit made against an older version")
        void refusesStaleEdit() {
            assertThat(errorOf(() -> hanoi().updateDetails("X", "Y", null, 3L)))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }
    }

    @Nested
    @DisplayName("resizing the map (BR-06)")
    class Resizing {

        @Test
        @DisplayName("grows freely")
        void grows() {
            Warehouse warehouse = hanoi();

            warehouse.resizeMap(new BigDecimal("50"), new BigDecimal("30"), MapExtent.EMPTY);

            assertThat(warehouse.mapWidth()).isEqualByComparingTo("50");
            assertThat(warehouse.mapHeight()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("shrinks down to exactly the farthest thing on the map")
        void shrinksToTheOccupiedExtent() {
            Warehouse warehouse = hanoi();

            warehouse.resizeMap(new BigDecimal("20"), new BigDecimal("10"),
                    new MapExtent(new BigDecimal("20"), new BigDecimal("10")));

            assertThat(warehouse.mapWidth()).isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("refuses to leave anything outside")
        void refusesToCutAnythingOff() {
            MapExtent occupied = new MapExtent(new BigDecimal("20.001"), new BigDecimal("5"));

            assertThat(errorOf(() -> hanoi().resizeMap(new BigDecimal("20"), BigDecimal.TEN, occupied)))
                    .isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
            assertThat(errorOf(() -> hanoi().resizeMap(BigDecimal.TEN, new BigDecimal("4.999"),
                    new MapExtent(BigDecimal.ONE, new BigDecimal("5"))))).isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("deactivates and reactivates; repeating either is harmless")
        void togglesIdempotently() {
            Warehouse warehouse = hanoi();

            warehouse.deactivate();
            warehouse.deactivate();
            assertThat(warehouse.status()).isEqualTo(WarehouseStatus.INACTIVE);

            warehouse.activate();
            warehouse.activate();
            assertThat(warehouse.status()).isEqualTo(WarehouseStatus.ACTIVE);
        }
    }
}

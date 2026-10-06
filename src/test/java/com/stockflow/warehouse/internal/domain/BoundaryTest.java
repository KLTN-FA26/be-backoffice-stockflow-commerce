package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Walls and doors: the rule {@code ck_boundary_kind} states, checked first with a reason. */
class BoundaryTest {

    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final UUID ID = UUID.fromString("0190a000-0000-7000-8000-000000000002");

    private static Segment from(String startX, String startY, String endX, String endY) {
        return new Segment(new BigDecimal(startX), new BigDecimal(startY), new BigDecimal(endX), new BigDecimal(endY));
    }

    private static final Segment NORTH = from("0", "0", "60", "0");

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Test
    @DisplayName("a wall is never passable and has no open/closed state")
    void wallRules() {
        assertThat(Boundary.create(ID, WAREHOUSE, BoundaryType.WALL, NORTH, false, null).type())
                .isEqualTo(BoundaryType.WALL);
        assertThat(errorOf(() -> Boundary.create(ID, WAREHOUSE, BoundaryType.WALL, NORTH, true, null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorOf(() -> Boundary.create(ID, WAREHOUSE, BoundaryType.WALL, NORTH, false, DoorStatus.OPEN)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a door needs an operational status; passable or not is its own choice")
    void doorRules() {
        assertThat(errorOf(() -> Boundary.create(ID, WAREHOUSE, BoundaryType.DOOR, NORTH, true, null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(Boundary.create(ID, WAREHOUSE, BoundaryType.DOOR, NORTH, false, DoorStatus.CLOSED).passable())
                .isFalse();
    }

    @Test
    @DisplayName("a segment needs two different ends and no negative coordinate")
    void segmentRules() {
        assertThat(errorOf(() -> from("5", "5", "5", "5"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorOf(() -> from("-1", "0", "5", "0"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(from("5", "5", "5.000", "6").endX()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a segment is on a map when both ends are; on its edge counts as on it")
    void segmentFitsWithinTheMap() {
        BigDecimal width = new BigDecimal("60");
        BigDecimal height = new BigDecimal("40");

        assertThat(NORTH.fitsWithin(width, height)).isTrue();
        assertThat(from("60", "2", "60", "8").fitsWithin(width, height)).isTrue();
        assertThat(from("59", "39", "61", "39").fitsWithin(width, height)).isFalse();
        assertThat(from("10", "41", "10", "30").fitsWithin(width, height)).isFalse();
    }

    @Test
    @DisplayName("an update may turn a wall into a door, under the same rules, from the current version")
    void updates() {
        Boundary boundary = Boundary.create(ID, WAREHOUSE, BoundaryType.WALL, NORTH, false, null);

        boundary.update(BoundaryType.DOOR, from("10", "0", "14", "0"), true, DoorStatus.OPEN, 0);

        assertThat(boundary.type()).isEqualTo(BoundaryType.DOOR);
        assertThat(boundary.operationalStatus()).isEqualTo(DoorStatus.OPEN);
        assertThat(errorOf(() -> boundary.update(BoundaryType.WALL, NORTH, true, null, 0)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorOf(() -> boundary.update(BoundaryType.WALL, NORTH, false, null, 1)))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }
}

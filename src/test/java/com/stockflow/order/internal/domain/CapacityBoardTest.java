package com.stockflow.order.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kltn-docs 06 §4.6 (BR-20, BR-21), 17 BR-25, C40-C43: every column of the capacity board from its six inputs, the
 * check of a volume against it, and the one crossing below zero that is told.
 */
class CapacityBoardTest {

    private static BigDecimal m3(String value) {
        return new BigDecimal(value);
    }

    /** 100 m³ of bins at 100%, 40 occupied, 30 incoming, 20 pending, 5 outgoing. */
    private static CapacityBoard board() {
        return new CapacityBoard(m3("100"), BigDecimal.ONE, m3("40"), m3("30"), m3("20"), m3("5"));
    }

    @Test
    @DisplayName("06 §4.6 step 3: every column, and outgoing never adds to free room")
    void columns() {
        CapacityBoard board = board();

        assertThat(board.capacityM3()).isEqualByComparingTo("100");
        assertThat(board.projectedFreeM3()).as("capacity - occupied - incoming").isEqualByComparingTo("30");
        assertThat(board.freeIfAllApprovedM3()).as("projected free - pending").isEqualByComparingTo("10");
        assertThat(board.occupiedPercent()).isEqualByComparingTo("40.00");
        assertThat(board.projectedFreePercent()).isEqualByComparingTo("30.00");
        assertThat(board.outgoingM3()).as("for reference only").isEqualByComparingTo("5");
        assertThat(new CapacityBoard(m3("100"), BigDecimal.ONE, m3("40"), m3("30"), m3("20"), m3("50"))
                .projectedFreeM3()).as("however much is held, the free room is the same").isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("BR-20: projected free and free if all approved go negative, and so does its percentage")
    void negative() {
        CapacityBoard board = new CapacityBoard(m3("10"), BigDecimal.ONE, m3("8"), m3("4"), m3("3"), null);

        assertThat(board.projectedFreeM3()).isEqualByComparingTo("-2");
        assertThat(board.freeIfAllApprovedM3()).isEqualByComparingTo("-5");
        assertThat(board.projectedFreePercent()).isEqualByComparingTo("-20.00");
        assertThat(board.occupiedPercent()).isEqualByComparingTo("80.00");
        assertThat(board.outgoingM3()).as("nothing held reads as zero").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("C43: a factor of 85% scales capacity, and with it every free column and percentage")
    void utilizationFactor() {
        CapacityBoard board = new CapacityBoard(m3("100"), new BigDecimal("0.85"), m3("40"), m3("30"), m3("20"),
                m3("0"));

        assertThat(board.capacityM3()).isEqualByComparingTo("85");
        assertThat(board.projectedFreeM3()).isEqualByComparingTo("15");
        assertThat(board.freeIfAllApprovedM3()).isEqualByComparingTo("-5");
        assertThat(board.occupiedPercent()).isEqualByComparingTo("47.06");
        assertThat(board.projectedFreePercent()).isEqualByComparingTo("17.65");

        CapacityBoard.Level shelf = CapacityBoard.level(m3("10"), new BigDecimal("0.85"), m3("4.25"));
        assertThat(shelf.capacityM3()).as("the factor applies at every level").isEqualByComparingTo("8.5");
        assertThat(shelf.freeM3()).isEqualByComparingTo("4.25");
        assertThat(shelf.occupiedPercent()).isEqualByComparingTo("50.00");
        assertThat(shelf.freePercent()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("C43: a factor outside (0, 1] is not a board")
    void factorBounds() {
        assertThatThrownBy(() -> new CapacityBoard(m3("1"), BigDecimal.ZERO, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityBoard(m3("1"), new BigDecimal("1.0001"), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no capacity (no usable bin, or none with a height - C40): the percentages are null, not a division")
    void noCapacity() {
        CapacityBoard board = new CapacityBoard(m3("0"), BigDecimal.ONE, m3("1.5"), m3("2"), m3("0"), m3("0"));

        assertThat(board.capacityM3()).isEqualByComparingTo("0");
        assertThat(board.projectedFreeM3()).isEqualByComparingTo("-3.5");
        assertThat(board.occupiedPercent()).isNull();
        assertThat(board.projectedFreePercent()).isNull();
        CapacityBoard.Level area = CapacityBoard.level(null, BigDecimal.ONE, m3("1.5"));
        assertThat(area.capacityM3()).as("an area has no capacity, only what stands in it").isNull();
        assertThat(area.occupiedM3()).isEqualByComparingTo("1.5");
        assertThat(area.freeM3()).isNull();
        assertThat(area.occupiedPercent()).isNull();
    }

    @Test
    @DisplayName("m³ to the litre: each input is rounded once, so the columns add up exactly")
    void rounding() {
        CapacityBoard board = new CapacityBoard(m3("8.4000004"), BigDecimal.ONE, m3("0.0845"), m3("1.0084999"),
                m3("0.0005"), null);

        assertThat(board.capacityM3()).isEqualTo(m3("8.400"));
        assertThat(board.occupiedM3()).isEqualTo(m3("0.085"));
        assertThat(board.incomingM3()).isEqualTo(m3("1.008"));
        assertThat(board.projectedFreeM3()).isEqualTo(m3("7.307"));
        assertThat(board.freeIfAllApprovedM3()).isEqualTo(m3("7.306"));
    }

    @Test
    @DisplayName("BR-25: a volume beyond projected free warns; beyond free if all approved warns of the others pending")
    void check() {
        CapacityBoard board = board(); // projected 30, if all approved 10

        CapacityBoard.CapacityCheck fits = board.check(m3("8"), null, null);
        assertThat(fits.exceedsProjected()).isFalse();
        assertThat(fits.exceedsIfAllApproved()).isFalse();
        assertThat(fits.shortfallM3()).isEqualByComparingTo("0");

        CapacityBoard.CapacityCheck othersPending = board.check(m3("12"), null, null);
        assertThat(othersPending.exceedsProjected()).isFalse();
        assertThat(othersPending.exceedsIfAllApproved()).as("the other production orders pending leave too little")
                .isTrue();

        CapacityBoard.CapacityCheck tooBig = board.check(m3("31"), null, null);
        assertThat(tooBig.exceedsProjected()).isTrue();
        assertThat(tooBig.exceedsIfAllApproved()).isTrue();
        assertThat(tooBig.shortfallM3()).isEqualByComparingTo("1");
        assertThat(tooBig.neededM3()).isEqualByComparingTo("31");

        CapacityBoard shortOfRoom = new CapacityBoard(m3("10"), BigDecimal.ONE, m3("8"), m3("4"), null, null);
        CapacityBoard.CapacityCheck nothing = shortOfRoom.check(BigDecimal.ZERO, null, null);
        assertThat(nothing.exceedsProjected()).as("nothing to make room for fits even a warehouse already short")
                .isFalse();
        assertThat(nothing.exceedsIfAllApproved()).isFalse();
        assertThat(nothing.shortfallM3()).isEqualByComparingTo("0");

        CapacityBoard.CapacityCheck unknown = board.check(null, null, null);
        assertThat(unknown.neededM3()).as("a carton of unknown size: nothing to judge").isNull();
        assertThat(unknown.exceedsProjected()).isFalse();
        assertThat(unknown.exceedsIfAllApproved()).isFalse();
    }

    @Test
    @DisplayName("BR-25: a production order is judged without its own cartons - pending while drawn up, incoming once sent")
    void checkWithoutItself() {
        CapacityBoard board = board(); // its 12 m³ are among the 20 pending: if all approved 10

        CapacityBoard.CapacityCheck draft = board.check(m3("12"), BigDecimal.ZERO, m3("12"));
        assertThat(draft.freeIfAllApprovedM3()).as("the others pending only: 30 - 8").isEqualByComparingTo("22");
        assertThat(draft.exceedsIfAllApproved()).isFalse();
        assertThat(draft.projectedFreeM3()).isEqualByComparingTo("30");

        // Sent, its 12 m³ are among the 30 incoming: the board without them has 42 projected free.
        CapacityBoard sentBoard = new CapacityBoard(m3("100"), BigDecimal.ONE, m3("40"), m3("30"), m3("8"), null);
        CapacityBoard.CapacityCheck sent = sentBoard.check(m3("12"), m3("12"), BigDecimal.ZERO);
        assertThat(sent.projectedFreeM3()).isEqualByComparingTo("42");
        assertThat(sent.exceedsProjected()).isFalse();
    }

    @Test
    @DisplayName("C42: only the send that crosses from zero or more to below zero is told, with how far below")
    void turnsNegative() {
        CapacityBoard board = new CapacityBoard(m3("10"), BigDecimal.ONE, m3("6"), m3("3"), null, null); // 1 free

        assertThat(board.turnsNegativeWith(m3("1"))).as("down to exactly zero is not short").isEmpty();
        assertThat(board.turnsNegativeWith(m3("1.25"))).hasValueSatisfying(shortfall ->
                assertThat(shortfall).isEqualByComparingTo("0.25"));
        assertThat(board.turnsNegativeWith(null)).as("a volume nobody knows tells nothing").isEmpty();

        CapacityBoard exactlyFull = new CapacityBoard(m3("10"), BigDecimal.ONE, m3("6"), m3("4"), null, null);
        assertThat(exactlyFull.turnsNegativeWith(m3("0.5"))).as("from zero, it crosses").isPresent();

        CapacityBoard alreadyShort = new CapacityBoard(m3("10"), BigDecimal.ONE, m3("8"), m3("4"), null, null);
        assertThat(alreadyShort.turnsNegativeWith(m3("5"))).as("already below zero: not told again").isEmpty();
    }
}

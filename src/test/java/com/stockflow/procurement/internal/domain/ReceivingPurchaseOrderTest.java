package com.stockflow.procurement.internal.domain;

import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder.Line;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder.LineStatus;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** kltn-docs warehouse/02 BR-04 (over-receipt tolerance) and 03 BR-01: the purchase order as receiving sees it. */
class ReceivingPurchaseOrderTest {

    private static Line line(String ordered, LineStatus status) {
        return new Line(UUID.randomUUID(), 1, UUID.randomUUID(), new BigDecimal(ordered), status);
    }

    private static ReceivingPurchaseOrder order(Status status, boolean rejected, Line... lines) {
        return new ReceivingPurchaseOrder(UUID.randomUUID(), "PO-1", status, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, rejected, List.of(lines));
    }

    @ParameterizedTest
    @CsvSource({
            "100,0,100",
            "100,5,105",
            "100,5.5,105",
            "10,2.5,10",
            "10,10,11",
            "3,33.33,3",
            "3,34,4",
            "1,99.99,1",
            "1,100,2",
            "2.5,0,2",
            "0,50,0"})
    @DisplayName("BR-04: the most that may be received is ordered x (1 + tolerance / 100), rounded down to whole units")
    void receivableLimit(String ordered, String tolerance, int expected) {
        assertThat(line(ordered, LineStatus.OPEN).receivableLimit(new BigDecimal(tolerance))).isEqualTo(expected);
    }

    @Test
    @DisplayName("a line is open while OPEN or PARTIALLY_RECEIVED, and only then takes goods")
    void lineOpen() {
        assertThat(line("1", LineStatus.OPEN).open()).isTrue();
        assertThat(line("1", LineStatus.PARTIALLY_RECEIVED).open()).isTrue();
        for (LineStatus closed : List.of(LineStatus.RECEIVED, LineStatus.CLOSED, LineStatus.CANCELLED)) {
            assertThat(line("1", closed).open()).as(closed.name()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    @DisplayName("BR-01 (03): only a CONFIRMED or PARTIALLY_RECEIVED order is receivable")
    void receivableStatuses(Status status) {
        boolean expected = status == Status.CONFIRMED || status == Status.PARTIALLY_RECEIVED;
        assertThat(order(status, false).receivable()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an order the supplier rejected is never receivable, whatever its status")
    void supplierRejected() {
        assertThat(order(Status.CONFIRMED, true).receivable()).isFalse();
        assertThat(order(Status.PARTIALLY_RECEIVED, true).receivable()).isFalse();
    }

    @Test
    @DisplayName("a missing tolerance is zero, the lines are copied, and an id is required")
    void construction() {
        ReceivingPurchaseOrder po = order(Status.CONFIRMED, false, line("5", LineStatus.OPEN));
        assertThat(po.tolerancePercent()).isEqualByComparingTo("0");
        assertThat(po.subcontract()).isFalse();
        assertThatThrownBy(() -> new ReceivingPurchaseOrder(null, "PO", Status.CONFIRMED, null, null, null, null, false,
                List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("line(id) finds a line of the order and nothing else")
    void lineLookup() {
        Line mine = line("5", LineStatus.OPEN);
        ReceivingPurchaseOrder po = order(Status.CONFIRMED, false, mine);
        assertThat(po.line(mine.id())).contains(mine);
        assertThat(po.line(UUID.randomUUID())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"10,0,OPEN", "10,-3,OPEN", "10,1,PARTIALLY_RECEIVED", "10,9,PARTIALLY_RECEIVED", "10,10,RECEIVED",
            "10,12,RECEIVED", "2.5,2,PARTIALLY_RECEIVED", "2.5,3,RECEIVED"})
    @DisplayName("a line's status from what has been counted in: none -> OPEN, some -> PARTIALLY_RECEIVED, "
            + "ordered or more (within tolerance) -> RECEIVED")
    void lineStatusFor(String ordered, int received, LineStatus expected) {
        assertThat(ReceivingPurchaseOrder.lineStatusFor(line(ordered, LineStatus.OPEN), received)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = LineStatus.class, names = {"RECEIVED", "CLOSED", "CANCELLED"})
    @DisplayName("a line that is not open keeps its status whatever is counted against it")
    void closedLineKeepsStatus(LineStatus status) {
        assertThat(ReceivingPurchaseOrder.lineStatusFor(line("10", status), 3)).isEqualTo(status);
    }

    @Test
    @DisplayName("order status after receiving: RECEIVED once nothing is open, PARTIALLY_RECEIVED while any line is")
    void statusAfter() {
        Line a = line("10", LineStatus.OPEN);
        Line b = line("10", LineStatus.OPEN);
        ReceivingPurchaseOrder po = order(Status.CONFIRMED, false, a, b);

        assertThat(po.statusAfter(Map.of(a.id(), LineStatus.RECEIVED, b.id(), LineStatus.PARTIALLY_RECEIVED)))
                .isEqualTo(Status.PARTIALLY_RECEIVED);
        assertThat(po.statusAfter(Map.of(a.id(), LineStatus.RECEIVED, b.id(), LineStatus.RECEIVED)))
                .isEqualTo(Status.RECEIVED);
        assertThat(po.statusAfter(Map.of(a.id(), LineStatus.RECEIVED)))
                .as("a line not touched keeps its own status (still OPEN)").isEqualTo(Status.PARTIALLY_RECEIVED);
    }

    @Test
    @DisplayName("a closed or cancelled line counts as nothing left to receive")
    void closedLinesAreNotOpen() {
        Line done = line("10", LineStatus.CLOSED);
        Line cancelled = line("10", LineStatus.CANCELLED);
        Line last = line("10", LineStatus.PARTIALLY_RECEIVED);
        ReceivingPurchaseOrder po = order(Status.PARTIALLY_RECEIVED, false, done, cancelled, last);
        assertThat(po.statusAfter(Map.of(last.id(), LineStatus.RECEIVED))).isEqualTo(Status.RECEIVED);
    }
}

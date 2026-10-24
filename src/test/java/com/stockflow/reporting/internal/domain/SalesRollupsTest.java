package com.stockflow.reporting.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The arithmetic of the sales rollups, with no database: place, cancel, cancel twice, replay, either order, the day line. */
class SalesRollupsTest {

    private static final LocalDate D24 = LocalDate.of(2026, 10, 24);
    private static final LocalDate D25 = LocalDate.of(2026, 10, 25);

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final SalesRollups rollups = new SalesRollups(ledger);

    private static OrderContribution order(UUID id, LocalDate day, long total, OrderContribution.Line... lines) {
        return new OrderContribution(id, day, "VND", BigDecimal.valueOf(total), List.of(lines));
    }

    private static OrderContribution.Line line(String sku, int quantity, long revenue) {
        return new OrderContribution.Line(sku, quantity, BigDecimal.valueOf(revenue));
    }

    private InMemoryLedger.Day day(LocalDate day) {
        return ledger.days.get(day);
    }

    @Test
    @DisplayName("a placement adds one order, its total and each line's units and revenue to the day it was placed on")
    void placeAdds() {
        UUID id = UUID.randomUUID();

        assertThat(rollups.place(order(id, D24, 36_000_000, line("CUP", 2, 24_000_000), line("LID", 4, 12_000_000))))
                .isTrue();

        assertThat(day(D24)).isEqualTo(new InMemoryLedger.Day(1, BigDecimal.valueOf(36_000_000)));
        assertThat(ledger.products.get("CUP@" + D24)).isEqualTo(new InMemoryLedger.Product(2, BigDecimal.valueOf(24_000_000)));
        assertThat(ledger.products.get("LID@" + D24)).isEqualTo(new InMemoryLedger.Product(4, BigDecimal.valueOf(12_000_000)));
    }

    @Test
    @DisplayName("the same placement replayed adds nothing")
    void replayedPlacementIsIgnored() {
        UUID id = UUID.randomUUID();
        OrderContribution placed = order(id, D24, 100, line("CUP", 1, 100));

        assertThat(rollups.place(placed)).isTrue();
        assertThat(rollups.place(placed)).isFalse();

        assertThat(day(D24)).isEqualTo(new InMemoryLedger.Day(1, BigDecimal.valueOf(100)));
        assertThat(ledger.products.get("CUP@" + D24).quantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("a cancellation takes the order out again, on the day it was PLACED")
    void cancelReversesOnThePlacedDay() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        rollups.place(order(id, D24, 100, line("CUP", 1, 100)));
        rollups.place(order(other, D24, 50, line("CUP", 1, 50)));

        assertThat(rollups.cancel(id, () -> null)).isTrue();

        assertThat(day(D24)).isEqualTo(new InMemoryLedger.Day(1, BigDecimal.valueOf(50)));
        assertThat(ledger.products.get("CUP@" + D24)).isEqualTo(new InMemoryLedger.Product(1, BigDecimal.valueOf(50)));
        assertThat(day(D25)).isNull();
    }

    @Test
    @DisplayName("a cancellation delivered twice takes the order out once")
    void cancelTwice() {
        UUID id = UUID.randomUUID();
        rollups.place(order(id, D24, 100, line("CUP", 1, 100)));

        assertThat(rollups.cancel(id, () -> null)).isTrue();
        assertThat(rollups.cancel(id, () -> null)).isFalse();

        assertThat(day(D24)).isEqualTo(new InMemoryLedger.Day(0, BigDecimal.ZERO));
        assertThat(ledger.products.get("CUP@" + D24).quantity()).isZero();
    }

    @Test
    @DisplayName("the cancellation takes out what the placement added, even if the order reads differently by then")
    void cancelReversesTheRecordedAmounts() {
        UUID id = UUID.randomUUID();
        rollups.place(order(id, D24, 100, line("CUP", 1, 100)));
        AtomicInteger asked = new AtomicInteger();

        rollups.cancel(id, () -> {
            asked.incrementAndGet();
            return order(id, D24, 999, line("CUP", 9, 999));
        });

        assertThat(asked).hasValue(0);
        assertThat(day(D24)).isEqualTo(new InMemoryLedger.Day(0, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("a cancellation that arrives before its placement counts the order in neither; the placement then does nothing")
    void cancelBeforePlace() {
        UUID id = UUID.randomUUID();
        OrderContribution placed = order(id, D24, 100, line("CUP", 1, 100));

        assertThat(rollups.cancel(id, () -> placed)).isTrue();
        assertThat(rollups.place(placed)).isFalse();

        assertThat(ledger.days).isEmpty();
        assertThat(ledger.products).isEmpty();
    }

    @Test
    @DisplayName("a cancellation for an order that cannot be read is skipped")
    void cancelOfAnUnknownOrder() {
        assertThat(rollups.cancel(UUID.randomUUID(), () -> null)).isFalse();
        assertThat(ledger.applied).isEmpty();
    }

    @Test
    @DisplayName("replaying the orders of a day after clearing it gives the same figures (what the rebuild does)")
    void rebuildGivesTheSameFigures() {
        UUID kept = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        OrderContribution keptOrder = order(kept, D24, 100, line("CUP", 1, 100));
        OrderContribution cancelledOrder = order(cancelled, D24, 40, line("CUP", 2, 40));
        rollups.place(keptOrder);
        rollups.place(cancelledOrder);
        rollups.cancel(cancelled, () -> null);
        var before = day(D24);
        var beforeProduct = ledger.products.get("CUP@" + D24);

        ledger.clearDay(D24);
        rollups.place(keptOrder);
        rollups.place(cancelledOrder);
        rollups.cancel(cancelled, () -> cancelledOrder);

        assertThat(day(D24)).isEqualTo(before);
        assertThat(ledger.products.get("CUP@" + D24)).isEqualTo(beforeProduct);
        // ...and an OrderCancelled delivered after the rebuild is already recorded: nothing moves.
        assertThat(rollups.cancel(cancelled, () -> cancelledOrder)).isFalse();
        assertThat(day(D24)).isEqualTo(before);
    }

    @Test
    @DisplayName("the day is the Asia/Ho_Chi_Minh day: 23:59:59 is still the day, 00:00:00 is the next one, whatever UTC says")
    void businessDayBoundary() {
        // 2026-10-24T16:59:59Z = 23:59:59 in Ho Chi Minh (UTC+7); one second later it is the 25th there.
        assertThat(BusinessDay.of(Instant.parse("2026-10-24T16:59:59Z"))).isEqualTo(D24);
        assertThat(BusinessDay.of(Instant.parse("2026-10-24T17:00:00Z"))).isEqualTo(D25);
        // 00:30 on the 25th in Ho Chi Minh is still the 24th in UTC.
        assertThat(BusinessDay.of(Instant.parse("2026-10-24T17:30:00Z"))).isEqualTo(D25);
        assertThat(BusinessDay.of(Instant.parse("2026-10-24T00:00:00Z"))).isEqualTo(D24);
        assertThat(BusinessDay.period(D24)).isEqualTo("2026-10-24");
    }
}

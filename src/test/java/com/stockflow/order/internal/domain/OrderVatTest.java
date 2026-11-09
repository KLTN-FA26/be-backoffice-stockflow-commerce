package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.FeeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-3 (kltn-docs 14 BR-09, BR-17, BR-20, BR-21) on the order: the total with tax is the lines and fee lines each with
 * its VAT, rounded line by line; money billed or closed a few units at a time is the units' share of the line with tax,
 * adding up to the line exactly; the fee lines are never closed with units.
 */
class OrderVatTest {

    private static final BigDecimal TEN = new BigDecimal("10");

    /** 1,000 cups at 2,000 VND, 10% VAT; a plate fee of 500,000 and a shipping fee of 300,000, both 10%. */
    private static Order order() {
        OrderLine cups = Order.line(UUID.randomUUID(), new Sku("LY12-ACME-01"), 1000, Money.vnd(2_000), null)
                .withCartons(UUID.randomUUID(), 20, 50).withTax(TEN);
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 1), UUID.randomUUID(), UUID.randomUUID(),
                List.of(cups), Instant.now());
        order.attachFees(List.of(OrderFee.of(UUID.randomUUID(), FeeKind.PLATE_FEE, new Sku("LY12-ACME-01"),
                        Money.vnd(500_000), TEN),
                OrderFee.of(UUID.randomUUID(), FeeKind.SHIPPING_FEE, null, Money.vnd(300_000), TEN)));
        return order;
    }

    @Test
    @DisplayName("before tax, the tax line by line, and the total with it")
    void theThreeTotals() {
        Order order = order();
        assertThat(order.lines().getFirst().taxAmount()).isEqualTo(Money.vnd(200_000));
        assertThat(order.netTotal()).as("2,000,000 + 500,000 + 300,000").isEqualTo(Money.vnd(2_800_000));
        assertThat(order.taxTotal()).as("200,000 + 50,000 + 30,000").isEqualTo(Money.vnd(280_000));
        assertThat(order.total()).isEqualTo(Money.vnd(3_080_000));
        assertThatThrownBy(() -> order.attachFees(List.of(OrderFee.of(UUID.randomUUID(), FeeKind.SHIPPING_FEE, null,
                Money.vnd(1), TEN)))).as("fixed when placed").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a line's VAT rounds to the dong, half up, on the line - not on the sum")
    void roundingIsPerLine() {
        OrderLine odd = Order.line(UUID.randomUUID(), new Sku("A"), 3, Money.vnd(1_005), null).withTax(new BigDecimal("5"));
        assertThat(odd.taxAmount()).as("3,015 x 5% = 150.75").isEqualTo(Money.vnd(151));
        assertThat(odd.grossTotal()).isEqualTo(Money.vnd(3_166));
    }

    @Test
    @DisplayName("units billed or closed a few at a time are their share of the line with tax, adding up to it exactly")
    void sharesOfALineAddUp() {
        OrderLine cups = order().lines().getFirst();
        assertThat(cups.grossOf(333)).as("2,200,000 x 333 / 1000").isEqualTo(Money.vnd(732_600));
        assertThat(cups.grossOf(333).plus(cups.grossBetween(333, 1000))).isEqualTo(cups.grossTotal());
        OrderLine thirds = Order.line(UUID.randomUUID(), new Sku("B"), 3, Money.vnd(1_000), null).withTax(TEN);
        assertThat(thirds.grossOf(1).plus(thirds.grossBetween(1, 2)).plus(thirds.grossBetween(2, 3)))
                .as("rounded slices of 3,300 still make 3,300").isEqualTo(Money.vnd(3_300));
        OrderLine untaxed = Order.line(UUID.randomUUID(), new Sku("C"), 10, Money.vnd(500), null);
        assertThat(untaxed.grossOf(4)).as("no VAT: the units at their price").isEqualTo(Money.vnd(2_000));
        assertThatThrownBy(() -> cups.grossOf(1001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("what the order is worth with units closed: the rest of each line with tax, and every fee line")
    void billableKeepsTheFees() {
        Order order = order();
        assertThat(order.billable()).isEqualByComparingTo(order.total().amount());
        order.lines().getFirst().closeUnits(100);
        // 900 cups with tax (1,980,000) + the fees with tax (880,000).
        assertThat(order.billable()).isEqualByComparingTo("2860000");
        assertThat(order.closedAmount()).isEqualByComparingTo("220000");
    }

    @Test
    @DisplayName("an order placed without VAT (before 4B-3, checkout) totals its lines, as before")
    void noVatNoChange() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 2), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(UUID.randomUUID(), new Sku("D"), 2, Money.vnd(12_500_000), null)), Instant.now());
        assertThat(order.total()).isEqualTo(Money.vnd(25_000_000));
        assertThat(order.netTotal()).isEqualTo(order.total());
        assertThat(order.taxTotal()).isEqualTo(Money.vnd(0));
    }
}

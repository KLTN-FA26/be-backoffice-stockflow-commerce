package com.stockflow.invoicing.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A line's arithmetic: amount = quantity x price, VAT rounded half up to the dong, line by line (14 BR-21). */
class EInvoiceLineTest {

    private static final UUID ORDER_LINE = UUID.randomUUID();

    private static EInvoiceLine goods(int cartons, String cartonPrice, String rate) {
        return EInvoiceLine.goods(UUID.randomUUID(), 1, ORDER_LINE, "CUP12-0312345678-01", "Ly giấy 12oz", "Thùng",
                cartons, 50, new BigDecimal(cartonPrice), new BigDecimal(rate));
    }

    @Test
    @DisplayName("cartons x the carton price, and VAT at the rate rounded half up")
    void amountAndVat() {
        EInvoiceLine line = goods(20, "100000", "10");
        assertThat(line.amount()).isEqualByComparingTo("2000000");
        assertThat(line.taxAmount()).isEqualByComparingTo("200000");

        // 3 x 33,335 = 100,005 at 8% = 8,000.4 -> 8,000; at 8.5% = 8,500.425 -> 8,500; 1 x 25 at 10% = 2.5 -> 3
        assertThat(goods(3, "33335", "8").taxAmount()).isEqualByComparingTo("8000");
        assertThat(goods(3, "33335", "8.5").taxAmount()).isEqualByComparingTo("8500");
        assertThat(goods(1, "25", "10").taxAmount()).as("half rounds up").isEqualByComparingTo("3");
        assertThat(goods(5, "100000", "0").taxAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a fee is one line of its amount")
    void feeLine() {
        EInvoiceLine fee = EInvoiceLine.fee(UUID.randomUUID(), 2, EInvoiceLine.FeeKind.SHIPPING_FEE, null,
                "Phí vận chuyển", "Lần", new BigDecimal("33333"), new BigDecimal("10"));
        assertThat(fee.quantity()).isEqualTo(1);
        assertThat(fee.amount()).isEqualByComparingTo("33333");
        assertThat(fee.taxAmount()).isEqualByComparingTo("3333");
        assertThat(fee.orderLineId()).isNull();
    }

    @Test
    @DisplayName("a line cannot disagree with itself: the amount and the tax are checked against quantity, price, rate")
    void aLineCannotDisagreeWithItself() {
        assertThatThrownBy(() -> new EInvoiceLine(UUID.randomUUID(), 1, EInvoiceLine.Kind.GOODS, null, ORDER_LINE,
                "SKU", "Ly", "Thùng", 2, 50, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("201"),
                new BigDecimal("20"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EInvoiceLine(UUID.randomUUID(), 1, EInvoiceLine.Kind.GOODS, null, ORDER_LINE,
                "SKU", "Ly", "Thùng", 2, 50, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("200"),
                new BigDecimal("21"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("what a line must be: numbered from 1, named, a quantity, whole-dong price, a rate below 100")
    void refusals() {
        assertThatThrownBy(() -> goods(0, "100", "10")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goods(1, "-1", "10")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goods(1, "100.5", "10")).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> goods(1, "100", "100")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goods(1, "100", "8.125")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EInvoiceLine.goods(UUID.randomUUID(), 0, ORDER_LINE, "SKU", "Ly", "Thùng", 1, 50,
                BigDecimal.TEN, BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EInvoiceLine.goods(UUID.randomUUID(), 1, ORDER_LINE, "SKU", " ", "Thùng", 1, 50,
                BigDecimal.TEN, BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EInvoiceLine.goods(UUID.randomUUID(), 1, null, "SKU", "Ly", "Thùng", 1, 50,
                BigDecimal.TEN, BigDecimal.TEN)).as("goods name their order line")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an adjustment's line may be below zero, with its tax below zero too (TT 91 Điều 10 khoản 6 điểm c)")
    void belowZeroForAnAdjustment() {
        EInvoiceLine reduced = goods(-2, "100025", "10");
        assertThat(reduced.amount()).isEqualByComparingTo("-200050");
        assertThat(reduced.taxAmount()).isEqualByComparingTo("-20005");
    }

    @Test
    @DisplayName("renaming changes the name and nothing else")
    void renamed() {
        EInvoiceLine line = goods(20, "100000", "10");
        EInvoiceLine renamed = line.renamed("  Ly giấy 12oz in logo  ");
        assertThat(renamed.itemName()).isEqualTo("Ly giấy 12oz in logo");
        assertThat(renamed.renamed(line.itemName())).isEqualTo(line);
    }
}

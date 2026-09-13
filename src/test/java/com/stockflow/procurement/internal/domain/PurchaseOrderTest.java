package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for purchase order creation (SCRUM-113/WBS 3.2.1) — no Spring, no database, same
 * style as {@code ProductTest}: the aggregate's own guarantees only.
 */
class PurchaseOrderTest {

    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final LocalDate EXPECTED = LocalDate.parse("2026-10-01");

    private static PoLine line(int qty, long unitPrice) {
        return new PoLine(UUID.randomUUID(), new Sku("SOFA-3S-GREY"), "Grey sofa", qty, 0,
                Money.vnd(unitPrice));
    }

    @Nested
    @DisplayName("draft")
    class Draft {

        @Test
        @DisplayName("is born DRAFT with a total derived from its lines")
        void draftComputesTotalFromLines() {
            PurchaseOrder order = PurchaseOrder.draft("PO-20261001-000001", SUPPLIER,
                    Money.VND, List.of(line(3, 100_000), line(2, 50_000)), EXPECTED);

            assertThat(order.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(order.totalAmount()).isEqualTo(Money.vnd(400_000));
            assertThat(order.lines()).hasSize(2);
        }

        @Test
        @DisplayName("rejected with zero lines")
        void zeroLinesRejected() {
            assertThatThrownBy(() -> PurchaseOrder.draft("PO-20261001-000001", SUPPLIER,
                    Money.VND, List.of(), EXPECTED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejected when a line's currency does not match the order's")
        void mismatchedLineCurrencyRejected() {
            PoLine usdLine = new PoLine(UUID.randomUUID(), new Sku("SOFA-3S-GREY"), "x", 1, 0,
                    new Money(java.math.BigDecimal.TEN, java.util.Currency.getInstance("USD")));

            assertThatThrownBy(() -> PurchaseOrder.draft("PO-20261001-000001", SUPPLIER,
                    Money.VND, List.of(usdLine), EXPECTED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("blank PO number rejected")
        void blankPoNumberRejected() {
            assertThatThrownBy(() -> PurchaseOrder.draft("  ", SUPPLIER,
                    Money.VND, List.of(line(1, 1000)), EXPECTED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PoLine")
    class PoLineInvariants {

        @Test
        @DisplayName("zero or negative quantityOrdered rejected")
        void nonPositiveQuantityRejected() {
            assertThatThrownBy(() -> new PoLine(UUID.randomUUID(), new Sku("SOFA-3S-GREY"), "x",
                    0, 0, Money.vnd(1000)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative unitPrice rejected")
        void negativeUnitPriceRejected() {
            assertThatThrownBy(() -> new PoLine(UUID.randomUUID(), new Sku("SOFA-3S-GREY"), "x",
                    1, 0, new Money(java.math.BigDecimal.valueOf(-1), Money.VND)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("openQuantity is quantityOrdered minus quantityReceived")
        void openQuantityComputed() {
            PoLine line = new PoLine(UUID.randomUUID(), new Sku("SOFA-3S-GREY"), "x", 10, 4,
                    Money.vnd(1000));

            assertThat(line.openQuantity()).isEqualTo(6);
        }
    }
}

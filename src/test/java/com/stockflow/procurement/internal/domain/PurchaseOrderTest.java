package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    @Nested
    @DisplayName("transitions (SCRUM-116/WBS 3.2.4)")
    class Transitions {

        private PurchaseOrder draftOrder() {
            return PurchaseOrder.draft("PO-20261001-000001", SUPPLIER, Money.VND,
                    List.of(line(10, 100_000)), EXPECTED);
        }

        @Test
        @DisplayName("approve: DRAFT -> APPROVED")
        void approveSucceeds() {
            PurchaseOrder order = draftOrder();

            order.approve(Instant.now());

            assertThat(order.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
        }

        @Test
        @DisplayName("approve rejected outside DRAFT")
        void approveFromNonDraftRejected() {
            PurchaseOrder order = draftOrder();
            order.approve(Instant.now());

            assertThatThrownBy(() -> order.approve(Instant.now()))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        @DisplayName("send: APPROVED -> SENT")
        void sendSucceeds() {
            PurchaseOrder order = draftOrder();
            order.approve(Instant.now());

            order.send(Instant.now());

            assertThat(order.status()).isEqualTo(PurchaseOrderStatus.SENT);
        }

        @Test
        @DisplayName("send rejected from DRAFT")
        void sendFromDraftRejected() {
            PurchaseOrder order = draftOrder();

            assertThatThrownBy(() -> order.send(Instant.now()))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        @DisplayName("cancel from DRAFT records the reason")
        void cancelFromDraftSucceeds() {
            PurchaseOrder order = draftOrder();

            order.cancel("supplier discontinued the item", Instant.now());

            assertThat(order.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
            assertThat(order.cancellationReason()).isEqualTo("supplier discontinued the item");
        }

        @Test
        @DisplayName("cancel refused once anything has been received")
        void cancelAfterReceiptRejected() {
            PurchaseOrder order = draftOrder();
            order.approve(Instant.now());
            order.send(Instant.now());
            UUID lineId = order.lines().get(0).id();
            order.receiveGoods(Map.of(lineId, 1), Instant.now());

            assertThatThrownBy(() -> order.cancel("changed my mind", Instant.now()))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        @DisplayName("cancel rejected once already CANCELLED")
        void cancelFromCancelledRejected() {
            PurchaseOrder order = draftOrder();
            order.cancel("first reason", Instant.now());

            assertThatThrownBy(() -> order.cancel("second reason", Instant.now()))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }
    }

    @Nested
    @DisplayName("receiveGoods (SCRUM-116/WBS 3.2.4.1)")
    class ReceiveGoods {

        private PurchaseOrder sentOrder(int qty) {
            PurchaseOrder order = PurchaseOrder.draft("PO-20261001-000001", SUPPLIER, Money.VND,
                    List.of(line(qty, 100_000)), EXPECTED);
            order.approve(Instant.now());
            order.send(Instant.now());
            return order;
        }

        @Test
        @DisplayName("partial receipt moves the order to PARTIALLY_RECEIVED")
        void partialReceiptSucceeds() {
            PurchaseOrder order = sentOrder(10);
            UUID lineId = order.lines().get(0).id();

            order.receiveGoods(Map.of(lineId, 4), Instant.now());

            assertThat(order.status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
            assertThat(order.lines().get(0).quantityReceived()).isEqualTo(4);
            assertThat(order.lines().get(0).openQuantity()).isEqualTo(6);
        }

        @Test
        @DisplayName("receiving every open unit closes the order")
        void fullReceiptClosesOrder() {
            PurchaseOrder order = sentOrder(10);
            UUID lineId = order.lines().get(0).id();

            order.receiveGoods(Map.of(lineId, 6), Instant.now());
            order.receiveGoods(Map.of(lineId, 4), Instant.now());

            assertThat(order.status()).isEqualTo(PurchaseOrderStatus.CLOSED);
            assertThat(order.lines().get(0).openQuantity()).isZero();
        }

        @Test
        @DisplayName("over-receipt rejected")
        void overReceiptRejected() {
            PurchaseOrder order = sentOrder(10);
            UUID lineId = order.lines().get(0).id();

            assertThatThrownBy(() -> order.receiveGoods(Map.of(lineId, 11), Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("receiving against an unknown line rejected")
        void unknownLineRejected() {
            PurchaseOrder order = sentOrder(10);

            assertThatThrownBy(() -> order.receiveGoods(Map.of(UUID.randomUUID(), 1), Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("receiving before the order is SENT rejected")
        void receiveBeforeSentRejected() {
            PurchaseOrder order = PurchaseOrder.draft("PO-20261001-000001", SUPPLIER, Money.VND,
                    List.of(line(10, 100_000)), EXPECTED);
            UUID lineId = order.lines().get(0).id();

            assertThatThrownBy(() -> order.receiveGoods(Map.of(lineId, 1), Instant.now()))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }
    }

    @Nested
    @DisplayName("closeShort")
    class CloseShort {

        @Test
        @DisplayName("PARTIALLY_RECEIVED -> CLOSED_SHORT records the reason")
        void closeShortSucceeds() {
            PurchaseOrder order = PurchaseOrder.draft("PO-20261001-000001", SUPPLIER, Money.VND,
                    List.of(line(10, 100_000)), EXPECTED);
            order.approve(Instant.now());
            order.send(Instant.now());
            UUID lineId = order.lines().get(0).id();
            order.receiveGoods(Map.of(lineId, 4), Instant.now());

            order.closeShort("supplier could not fulfil the remainder", Instant.now());

            assertThat(order.status()).isEqualTo(PurchaseOrderStatus.CLOSED_SHORT);
            assertThat(order.closeShortReason()).isEqualTo("supplier could not fulfil the remainder");
        }

        @Test
        @DisplayName("rejected from SENT (nothing received yet)")
        void closeShortFromSentRejected() {
            PurchaseOrder order = PurchaseOrder.draft("PO-20261001-000001", SUPPLIER, Money.VND,
                    List.of(line(10, 100_000)), EXPECTED);
            order.approve(Instant.now());
            order.send(Instant.now());

            assertThatThrownBy(() -> order.closeShort("reason", Instant.now()))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }
    }
}

package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-7 (kltn-docs 17 §4.2 steps 2-3, BR-22): an order of a contract ships from one warehouse, chosen once it is
 * confirmed, changed only while nothing is held, and released from it only.
 */
class OrderShippingWarehouseTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HAN = UUID.randomUUID();

    /** A quote's order, its contract signed with nothing due on signing: CONFIRMED. */
    private static Order confirmed() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 9), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ-ACME"), 1_000, Money.vnd(2_000), null)), NOW);
        order.applyTerms(PaymentTerm.PREPAID, null);
        order.sendContract(SALES, NOW);
        order.contractSigned(false, false, SALES, NOW);
        return order;
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("chosen once confirmed, with who and when; the same warehouse again changes nothing")
    void chosen() {
        Order order = confirmed();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.chooseShippingWarehouse(HCM, false, SALES, NOW)).isTrue();
        assertThat(order.shippingWarehouseId()).isEqualTo(HCM);
        assertThat(order.shippingWarehouseChosenBy()).isEqualTo(SALES);
        assertThat(order.chooseShippingWarehouse(HCM, true, SALES, NOW.plusSeconds(60))).isFalse();
        assertThat(order.shippingWarehouseChosenAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("BR-22: another warehouse while nothing is held, never once stock is held there")
    void changedOnlyWhileNothingIsHeld() {
        Order order = confirmed();
        order.chooseShippingWarehouse(HCM, false, SALES, NOW);
        assertThat(order.chooseShippingWarehouse(HAN, false, SALES, NOW)).isTrue();
        assertThatThrownBy(() -> order.chooseShippingWarehouse(HCM, true, SALES, NOW))
                .extracting(OrderShippingWarehouseTest::code).isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_LOCKED);
        assertThat(order.shippingWarehouseId()).isEqualTo(HAN);
    }

    @Test
    @DisplayName("not for an order waiting for its contract's money, nor for one not of a contract")
    void onlyAConfirmedOrderOfAContract() {
        Order waiting = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 10), UUID.randomUUID(),
                UUID.randomUUID(), List.of(Order.line(new Sku("CUP-12OZ-ACME"), 1_000, Money.vnd(2_000), null)), NOW);
        waiting.applyTerms(PaymentTerm.PREPAID, null);
        waiting.sendContract(SALES, NOW);
        waiting.contractSigned(false, true, SALES, NOW);
        assertThatThrownBy(() -> waiting.chooseShippingWarehouse(HCM, false, SALES, NOW))
                .extracting(OrderShippingWarehouseTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED);

        Order checkout = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 11), UUID.randomUUID(),
                UUID.randomUUID(), List.of(Order.line(new Sku("SOFA-3S-GREY"), 1, Money.vnd(1_000_000), null)), NOW);
        assertThatThrownBy(() -> checkout.chooseShippingWarehouse(HCM, false, SALES, NOW))
                .extracting(OrderShippingWarehouseTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("released from its shipping warehouse only, and not before one is chosen")
    void releasedFromItsShippingWarehouse() {
        Order order = confirmed();
        order.lines().forEach(line -> order.holdMore(line.id(), List.of(UUID.randomUUID())));
        assertThatThrownBy(() -> order.release(HCM, "HCM", SALES, NOW)).extracting(OrderShippingWarehouseTest::code)
                .isEqualTo(ErrorCode.ORDER_STOCK_NOT_HELD);
        order.chooseShippingWarehouse(HCM, true, SALES, NOW);
        assertThatThrownBy(() -> order.release(HAN, "HAN", SALES, NOW)).extracting(OrderShippingWarehouseTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_MISMATCH);
        order.release(HCM, "HCM", SALES, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(order.warehouseId()).isEqualTo(HCM);
    }

    @Test
    @DisplayName("a line held over time: holds added, never twice")
    void heldOverTime() {
        Order order = confirmed();
        UUID line = order.lines().getFirst().id();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        order.holdMore(line, List.of(first));
        order.holdMore(line, List.of(first, second));
        assertThat(order.lines().getFirst().reservationIds()).containsExactly(first, second);
    }
}

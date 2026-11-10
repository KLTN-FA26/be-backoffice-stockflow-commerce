package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
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
 * 4B-6: an order of a signed contract is paid by its installments (kltn-docs 15 BR-07, BR-08, BR-13) - confirmed when
 * those on signing are paid, its goods held until those before delivery are. Since 4B-10 the order keeps no money of its
 * own: what its installments still owe is handed to it by the caller (read under the order's lock), and an order
 * without a contract - one placed at checkout - has nothing in the system that holds its goods back.
 */
class OrderInstallmentsTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID SALES = UUID.randomUUID();

    /** A quote's order, its contract sent and signed with an installment on signing: PENDING_PAYMENT. */
    private static Order signedWaitingOnSigning() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 7), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ-ACME"), 1_000, Money.vnd(2_000), null)), NOW);
        order.sendContract(SALES, NOW);
        order.contractSigned(false, true, SALES, NOW);
        return order;
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("its installments on signing paid: PENDING_PAYMENT -> CONFIRMED, once")
    void confirmedByInstallments() {
        Order order = signedWaitingOnSigning();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.confirmByInstallments(NOW)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.confirmByInstallments(NOW)).as("a redelivered event changes nothing").isFalse();
    }

    @Test
    @DisplayName("an order whose contract is not signed yet is never confirmed by installments")
    void unsignedOrderIsNotConfirmedByInstallments() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 8), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("SOFA-3S-GREY"), 1, Money.vnd(1_000_000), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.sendContract(SALES, NOW);
        assertThat(order.confirmByInstallments(NOW)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONTRACT);
    }

    @Test
    @DisplayName("its goods wait for the installments paid before delivery, not for its placeholder terms (BR-08)")
    void handoverWaitsForInstallmentsBeforeDelivery() {
        Order order = signedWaitingOnSigning();
        order.confirmByInstallments(NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        // 4B-7: released from its shipping warehouse, where its stock is held.
        UUID warehouse = UUID.randomUUID();
        order.chooseShippingWarehouse(warehouse, true, SALES, NOW);
        order.release(warehouse, "HCM", SALES, NOW);
        order.startPicking(SALES, NOW);

        assertThatThrownBy(() -> order.requireHandoverAllowed(new BigDecimal("600000")))
                .extracting(OrderInstallmentsTest::code).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
        assertThatThrownBy(() -> order.recordGoodsLeft(NOW, null, new BigDecimal("1")))
                .as("SCRUM-392 LP-3: goods leaving on a load plan's vehicle go through the same gate")
                .extracting(OrderInstallmentsTest::code).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
        assertThat(order.goodsLeftAt()).isNull();
        order.requireHandoverAllowed(BigDecimal.ZERO);
        order.requireHandoverAllowed(null);
        assertThat(order.amountDueBeforeHandover()).as("the order itself knows of no money").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("17 §4.6 step 21: delivered, it completes and closes only once its installments owe nothing")
    void completesAndClosesOnlyWhenNothingIsOwed() {
        Order order = signedWaitingOnSigning();
        order.confirmByInstallments(NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        UUID warehouse = UUID.randomUUID();
        order.chooseShippingWarehouse(warehouse, true, SALES, NOW);
        order.release(warehouse, "HCM", SALES, NOW);
        order.markPacked(NOW, SALES);
        order.markShipped(NOW, SALES, BigDecimal.ZERO);
        order.markDelivered(NOW, SALES);
        Instant later = NOW.plus(java.time.Duration.ofDays(40));

        assertThat(order.complete(later, java.time.Duration.ofDays(7), false, true)).as("still owing").isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.complete(later, java.time.Duration.ofDays(7), false, false)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);

        Instant muchLater = later.plus(java.time.Duration.ofDays(31));
        assertThat(order.close(muchLater, java.time.Duration.ofDays(30), true))
                .as("a debt is never put out of the order's sight").isFalse();
        assertThat(order.close(muchLater, java.time.Duration.ofDays(30), false)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    @DisplayName("a payment hold of a contract order is resolved on its installments, which the service checks")
    void paymentHoldOfAContractOrder() {
        Order order = signedWaitingOnSigning();
        order.confirmByInstallments(NOW);
        order.putOnHold(HoldReason.PAYMENT, "Payment receipt FT1 voided", SALES, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);

        order.resolveHold("Receipt FT2 recorded", SALES, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.holdReason()).isNull();
    }
}

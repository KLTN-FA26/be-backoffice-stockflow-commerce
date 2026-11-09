package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of 4A-7: an order on hold is followed by the delay alerts only when the status it was held from is followed - a
 * credit hold placed when the contract was signed has none, and its order is not confirmed. An order waiting for stock
 * (4A-6) is followed.
 */
class DelayAlertFollowedTest {

    @Test
    @DisplayName("ON_HOLD follows the status it was held from; a credit hold at signing is not followed")
    void onHold() {
        for (var stages : java.util.List.of(DelayAlertService.SHORTAGE_FOLLOWED, DelayAlertService.DELIVERY_FOLLOWED)) {
            assertThat(DelayAlertService.followed(stages, OrderStatus.ON_HOLD, OrderStatus.CONFIRMED)).isTrue();
            assertThat(DelayAlertService.followed(stages, OrderStatus.ON_HOLD, OrderStatus.PICKING)).isTrue();
            assertThat(DelayAlertService.followed(stages, OrderStatus.ON_HOLD, null)).isFalse();
            assertThat(DelayAlertService.followed(stages, OrderStatus.ON_HOLD, OrderStatus.IN_PRODUCTION)).isFalse();
            assertThat(DelayAlertService.followed(stages, OrderStatus.CONFIRMED, null)).isTrue();
            assertThat(DelayAlertService.followed(stages, OrderStatus.PENDING_PAYMENT, null)).isFalse();
            assertThat(DelayAlertService.followed(stages, OrderStatus.SHIPPED, null)).isFalse();
            assertThat(stages).doesNotContain(OrderStatus.ON_HOLD);
        }
    }

    @Test
    @DisplayName("4A-6 with 4A-7: an order waiting for stock is followed and may get more production orders")
    void awaitingStock() {
        for (var stages : java.util.List.of(DelayAlertService.SHORTAGE_FOLLOWED, DelayAlertService.DELIVERY_FOLLOWED)) {
            assertThat(DelayAlertService.followed(stages, OrderStatus.AWAITING_STOCK, null)).isTrue();
            assertThat(DelayAlertService.followed(stages, OrderStatus.ON_HOLD, OrderStatus.AWAITING_STOCK)).isTrue();
        }
        assertThat(ProductionOrderService.PRODUCIBLE).contains(OrderStatus.CONFIRMED, OrderStatus.AWAITING_STOCK)
                .doesNotContain(OrderStatus.ON_HOLD);
    }
}

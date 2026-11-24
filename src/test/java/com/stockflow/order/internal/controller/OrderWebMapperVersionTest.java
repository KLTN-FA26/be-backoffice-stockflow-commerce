package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 Q-12: the lost-shipment closure takes the order's version back, so the order the back office reads carries it -
 * on the detail, after F-1's facts are added to it, and on a write's answer.
 */
class OrderWebMapperVersionTest {

    private static OrderSummary order(Long version) {
        return new OrderSummary(UUID.randomUUID(), "SO-1", UUID.randomUUID(), OrderStatus.DELIVERY_FAILED,
                Money.vnd(1_000), List.of(), Instant.now(), null, null, null, null, null, null, null, null, null, null,
                null, null, null, version);
    }

    @Test
    @DisplayName("staff read the order's version, kept when the settlement facts are added; a customer reads none")
    void staffReadTheVersion() {
        OrderResponse staff = OrderWebMapper.toResponse(order(7L));
        assertThat(staff.version()).isEqualTo(7L);
        assertThat(staff.withSettlement(null, null).version()).isEqualTo(7L);
        assertThat(OrderWebMapper.toResponse(order(7L), false).version()).isNull();
    }

    @Test
    @DisplayName("a summary built without a version answers null rather than a made-up 0")
    void noVersionIsNull() {
        var bare = new OrderSummary(UUID.randomUUID(), "SO-2", UUID.randomUUID(), OrderStatus.CONFIRMED,
                Money.vnd(1_000), List.of(), Instant.now());
        assertThat(OrderWebMapper.toResponse(bare).version()).isNull();
    }
}

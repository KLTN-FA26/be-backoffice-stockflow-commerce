package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-8 (WF4, kltn-docs 08 BR-04, 17 §4.7, §4.8): the order response carries the time of the order's first shipping
 * label, so the back office hides Cancel and the delivery-address change from then on.
 */
class OrderWebMapperFirstLabelTest {

    private static OrderSummary orderLabelledAt(Instant firstLabeledAt) {
        var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), false, null, firstLabeledAt);
        return new OrderSummary(UUID.randomUUID(), "SO-1", UUID.randomUUID(), OrderStatus.PICKING, Money.vnd(1_000),
                List.of(), Instant.now(), null, null, null, null, null, null, null, null, null, null, null,
                lifecycle);
    }

    @Test
    @DisplayName("the first label's time is in the response, for staff and for a customer reading the same shape")
    void carriesTheFirstLabelTime() {
        Instant at = Instant.parse("2026-11-12T03:04:05Z");

        assertThat(OrderWebMapper.toResponse(orderLabelledAt(at)).firstLabeledAt()).isEqualTo(at);
        assertThat(OrderWebMapper.toResponse(orderLabelledAt(at), false).firstLabeledAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("an order nothing was labelled for answers null; a summary without a lifecycle block too")
    void nullBeforeTheFirstLabel() {
        assertThat(OrderWebMapper.toResponse(orderLabelledAt(null)).firstLabeledAt()).isNull();
        var bare = new OrderSummary(UUID.randomUUID(), "SO-2", UUID.randomUUID(), OrderStatus.CONFIRMED,
                Money.vnd(1_000), List.of(), Instant.now());
        assertThat(OrderWebMapper.toResponse(bare).firstLabeledAt()).isNull();
    }
}

package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of G3-2: {@code GET /orders/{id}} answers a customer with the public hold wording only - never why the shop
 * holds the order (FRAUD...), where it goes back, or the coordinator's note. Staff get all of it.
 */
class OrderWebMapperHoldPrivacyTest {

    private static OrderSummary heldFor(HoldReason reason) {
        var lifecycle = new OrderSummary.Lifecycle(reason, reason == HoldReason.CREDIT ? null : OrderStatus.PICKING,
                "nghi gian lận", null, null, null, null, null, null, null, null, null, null, List.of(), List.of());
        return new OrderSummary(UUID.randomUUID(), "SO-1", UUID.randomUUID(), OrderStatus.ON_HOLD, Money.vnd(1_000),
                List.of(), Instant.now(), null, null, null, null, null, null, null, null, null, null, null, null, null,
                lifecycle);
    }

    @Test
    @DisplayName("a customer reads UNDER_REVIEW for a fraud hold; staff read FRAUD, PICKING and the note")
    void fraudHold() {
        var customer = OrderWebMapper.toResponse(heldFor(HoldReason.FRAUD), false);
        assertThat(customer.holdReason()).isNull();
        assertThat(customer.statusBeforeHold()).isNull();
        assertThat(customer.holdNote()).isNull();
        assertThat(customer.holdReasonPublic()).isEqualTo("UNDER_REVIEW");

        var staff = OrderWebMapper.toResponse(heldFor(HoldReason.FRAUD), true);
        assertThat(staff.holdReason()).isEqualTo("FRAUD");
        assertThat(staff.statusBeforeHold()).isEqualTo("PICKING");
        assertThat(staff.holdNote()).isEqualTo("nghi gian lận");
        assertThat(staff.holdReasonPublic()).isEqualTo("UNDER_REVIEW");
    }

    @Test
    @DisplayName("a credit hold reads AWAITING_CREDIT_APPROVAL for everyone")
    void creditHold() {
        assertThat(OrderWebMapper.toResponse(heldFor(HoldReason.CREDIT), false).holdReasonPublic())
                .isEqualTo("AWAITING_CREDIT_APPROVAL");
    }
}

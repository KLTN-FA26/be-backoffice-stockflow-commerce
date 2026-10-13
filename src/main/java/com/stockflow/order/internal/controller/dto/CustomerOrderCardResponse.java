package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One order in the signed-in customer's list (SCRUM-245/264). Not {@link OrderResponse}: the list a
 * customer reads carries one unified status and nothing internal to the shop.
 */
@Schema(name = "CustomerOrderCard")
public record CustomerOrderCardResponse(
        UUID orderId,

        @Schema(example = "SO-20260907-000431")
        String orderNumber,

        @Schema(example = "CONFIRMED", description = "The order's own status, for staff-facing wording")
        String status,

        @Schema(example = "PREPARING", description = "What the customer reads: AWAITING_PAYMENT, UNDER_REVIEW, CONFIRMED, "
                + "IN_PRODUCTION, PREPARING, SHIPPING, DELIVERED, CANCELLED or RETURNED")
        String statusGroup,

        BigDecimal total,

        @Schema(example = "VND")
        String currency,

        Instant placedAt,

        int lineCount,

        @Schema(example = "NOT_SHIPPED", description = "NOT_SHIPPED, IN_TRANSIT, DELIVERED or RETURNED - only what "
                + "staff have recorded; nothing is tracked after handover to the carrier")
        String deliveryState
) {
}

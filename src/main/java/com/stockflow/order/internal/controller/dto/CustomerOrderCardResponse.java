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

        @Schema(example = "PREPARING", description = "What the customer reads: AWAITING_CONTRACT, AWAITING_PAYMENT, "
                + "UNDER_REVIEW, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION (legacy), PREPARING, SHIPPING, DELIVERED, "
                + "RETURN_REQUESTED (a return is being decided; the goods are still the customer's), CANCELLED, "
                + "RETURNED (the order ended in a return and nothing of it stayed with the customer) or "
                + "PARTIALLY_RETURNED (it ended in a return but the customer kept delivered goods). Parse tolerantly: "
                + "more may come")
        String statusGroup,

        BigDecimal total,

        @Schema(example = "VND")
        String currency,

        Instant placedAt,

        int lineCount,

        @Schema(example = "NOT_SHIPPED", description = "NOT_SHIPPED, IN_TRANSIT, PARTIALLY_DELIVERED, DELIVERY_FAILED, "
                + "DELIVERED, RETURNED or PARTIALLY_RETURNED (ended in a return, the customer kept delivered goods) - only "
                + "what staff have recorded; nothing is tracked after handover to the carrier. Parse tolerantly: more may "
                + "come")
        String deliveryState,

        @Schema(example = "PENDING", description = "The customer's latest cancellation request: PENDING, APPROVED or "
                + "REJECTED; null when they never asked (G3-2)")
        String cancellationRequestStatus,

        @Schema(example = "Ly giay in logo", description = "What the order's first line is: the name of the design its "
                + "SKU was made to, else the SKU itself, as a quote row's firstItemName; null for an order with no line")
        String firstItemName
) {
}

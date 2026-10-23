package com.stockflow.reporting.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many orders are in one status right now")
public record OrderStatusCountResponse(
        @Schema(description = "An order status; every status is listed, ON_HOLD included", example = "ON_HOLD")
        String status,
        @Schema(example = "3") long count) {
}

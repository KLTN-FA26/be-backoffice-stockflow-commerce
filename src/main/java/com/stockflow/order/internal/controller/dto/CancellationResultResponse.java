package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** What a customer's cancellation did: CANCELLED at once, or REQUESTED for Sales to decide. */
@Schema(name = "OrderCancellationResult")
public record CancellationResultResponse(
        @Schema(example = "REQUESTED") String result,
        @Schema(description = "Set when result is REQUESTED") UUID requestId,
        OrderResponse order) {
}

package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.internal.domain.CompletionHoldReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Pause an order's automatic completion while a delivery is verified (WF-05 GH-2).
 *
 * @param note required for OTHER
 */
@Schema(description = "Pause an order's automatic completion")
public record CompletionHoldRequest(

        @Schema(example = "DELIVERY_DISPUTED", description = "DELIVERY_DISPUTED or OTHER")
        @NotNull(message = "reasonCode is required")
        CompletionHoldReason reasonCode,

        @Schema(example = "Khách báo chưa nhận được thùng nào, đang xác minh với hãng")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

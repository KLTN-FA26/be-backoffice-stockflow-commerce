package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lift the pause on an order's completion: what the verification found, kept with the hold (WF-05 GH-2). */
@Schema(description = "Release an order's completion hold")
public record ReleaseCompletionHoldRequest(

        @Schema(example = "Hãng xác nhận đã giao, khách đã nhận đủ hàng")
        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

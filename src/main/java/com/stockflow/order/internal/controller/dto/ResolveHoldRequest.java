package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Resolve an order's hold: how it was settled, kept with the hold and in the history (G3-2). */
@Schema(description = "Resolve an order's hold")
public record ResolveHoldRequest(

        @Schema(example = "Khách đã xác nhận địa chỉ mới")
        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}

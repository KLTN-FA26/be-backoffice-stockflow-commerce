package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Cancel a purchase order")
public record CancelPurchaseOrderRequest(

        @NotBlank(message = "reason is required")
        @Size(max = 1000)
        String reason
) {
}

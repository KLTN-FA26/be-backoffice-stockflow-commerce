package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Cancel a purchase order")
public record CancelPurchaseOrderRequest(
        @NotBlank(message = "reason is required") @Size(max = 255) String reason) {}

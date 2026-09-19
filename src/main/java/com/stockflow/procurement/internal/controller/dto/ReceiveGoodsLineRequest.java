package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ReceiveGoodsLineRequest(

        @NotNull(message = "lineId is required")
        UUID lineId,

        @Positive(message = "quantity must be positive")
        int quantity
) {
}

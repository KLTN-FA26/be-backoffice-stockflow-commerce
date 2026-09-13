package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Create a purchase order")
public record CreatePurchaseOrderRequest(

        @NotNull(message = "supplierId is required")
        UUID supplierId,

        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
        @Schema(example = "VND")
        String currency,

        LocalDate expectedAt,

        @NotEmpty(message = "at least one line is required")
        @Valid
        List<CreatePOLineRequest> lines
) {
}

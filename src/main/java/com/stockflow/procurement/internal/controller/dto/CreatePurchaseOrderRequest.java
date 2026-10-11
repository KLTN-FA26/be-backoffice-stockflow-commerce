package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ValidProcurementFields
@Schema(description = "Create a purchase order")
public record CreatePurchaseOrderRequest(
        @NotNull(message = "supplierId is required") UUID supplierId,
        @Schema(description = "The warehouse the goods are received into (SCRUM-390)")
        @NotNull(message = "warehouseId is required") UUID warehouseId,
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
                @Schema(example = "VND")
                @NotNull
                String currency,
        LocalDate expectedAt,
        @Size(max = 2000) String note,
        @NotEmpty(message = "at least one line is required") @Valid
                List<@NotNull CreatePOLineRequest> lines) {}

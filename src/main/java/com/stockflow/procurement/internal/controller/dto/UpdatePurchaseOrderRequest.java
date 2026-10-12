package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The whole draft as the buyer now wants it (SCRUM-462). Lines not listed leave the order. Supplier
 * and currency cannot change (BR-07): cancel the draft and create another.
 */
@Schema(description = "Edit a DRAFT standard purchase order")
public record UpdatePurchaseOrderRequest(
        @Schema(description = "The version read with the order; a draft changed since answers 409 OPTIMISTIC_LOCK")
        @NotNull(message = "version is required") Long version,
        @Schema(description = "The warehouse the goods are received into (SCRUM-390)")
        @NotNull(message = "warehouseId is required") UUID warehouseId,
        @Schema(description = "A date in the past is accepted with the DELIVERY_DATE_IN_PAST warning (BR-06)")
        @NotNull(message = "expectedAt is required") LocalDate expectedAt,
        @Size(max = 2000) String note,
        @Size(max = 200, message = "At most 200 lines")
        @NotEmpty(message = "at least one line is required") @Valid
                List<@NotNull UpdatePOLineRequest> lines) {}

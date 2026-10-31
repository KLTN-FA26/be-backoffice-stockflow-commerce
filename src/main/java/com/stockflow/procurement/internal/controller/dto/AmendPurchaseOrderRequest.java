package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * An amendment of an APPROVED or CONFIRMED standard purchase order (SCRUM-117): the header as it should read
 * afterwards and <b>every line kept</b>. A live line not listed is removed; no line is added, and the unit, warehouse,
 * supplier and currency do not change (cancel the order and order again for those).
 */
@Schema(description = "Amend an approved or confirmed standard purchase order through a revision approved again")
public record AmendPurchaseOrderRequest(
        @Schema(description = "The version read with the order; an order changed since answers 409 OPTIMISTIC_LOCK")
        @NotNull Long version,
        @Schema(description = "Why the order changes; the approver reads it, and the supplier when the order was sent")
        @NotBlank @Size(max = 500) String reason,
        @Schema(description = "The expected delivery date as it should read afterwards; required")
        LocalDate expectedDate,
        @Schema(description = "The order note as it should read afterwards; empty clears it")
        @Size(max = 1000) String note,
        @Schema(description = "Every line kept; a line left out is removed")
        @NotEmpty List<@Valid AmendPurchaseOrderLineRequest> lines) {}

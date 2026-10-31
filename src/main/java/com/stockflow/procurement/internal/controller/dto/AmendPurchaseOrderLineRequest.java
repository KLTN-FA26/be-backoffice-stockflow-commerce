package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line kept by an amendment. The quantity, price and tax ranges are the aggregate's to check, so that every fault
 * of the request is reported at once, per line and field; the description's length is checked here, as on a draft
 * line, since it is the limit of the {@code note} column the line and its revision store it in.
 */
public record AmendPurchaseOrderLineRequest(
        @Schema(description = "A live line of the order; its SKU and unit stay")
        @NotNull UUID lineId,
        @Schema(description = "Base units, 1 to 1,000,000; a line in a packing unit stays a whole number of it")
        @NotNull Integer quantityOrdered,
        @Schema(description = "Omit to keep the line's price - never the catalog's of today: it was agreed at approval")
        BigDecimal unitPrice,
        @Schema(description = "0 to 100; omit to keep the line's")
        BigDecimal taxRate,
        @Schema(description = "Omit or leave blank to keep the line's")
        @Size(max = 255) String description) {}

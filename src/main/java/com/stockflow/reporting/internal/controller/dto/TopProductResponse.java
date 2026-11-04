package com.stockflow.reporting.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "A best-selling SKU of the range")
public record TopProductResponse(
        @Schema(example = "CUP-PAPER-12OZ") String sku,
        @Schema(description = "Null when the product module no longer knows the SKU", example = "Paper cup 12oz")
        String productName,
        @Schema(description = "Units sold in the range, less cancelled orders", example = "1200") long quantitySold,
        @Schema(description = "Revenue of those units, whole dong", example = "36000000") BigDecimal revenue) {
}

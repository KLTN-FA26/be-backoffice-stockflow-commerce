package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PUT replaces the purchasing information of one SKU's inventory item (docs 01 §3 "Thông tin mua hàng").
 * Every field is optional ("khuyến nghị"); a null one clears it. {@code version} is the inventory item's.
 */
public record SkuPurchasingRequest(
        @NotNull(message = "version is required") @Min(0)
        Long version,

        @Schema(description = "Must exist and be ACTIVE when it changes")
        UUID defaultSupplierId,

        @Positive(message = "referencePurchasePrice must be positive")
        @Digits(integer = 16, fraction = 2, message = "referencePurchasePrice has at most 2 decimals")
        BigDecimal referencePurchasePrice,

        @Schema(example = "VND", description = "Required with referencePurchasePrice, absent without it")
        @Pattern(regexp = "[A-Z]{3}", message = "referencePurchaseCurrency is a 3-letter ISO 4217 code")
        String referencePurchaseCurrency,

        @Min(value = 0, message = "leadTimeDays must be between 0 and 365")
        @Max(value = 365, message = "leadTimeDays must be between 0 and 365")
        Integer leadTimeDays,

        @Schema(description = "Minimum order quantity, in base units")
        @Min(value = 1, message = "moq must be at least 1")
        Integer moq
) {
}

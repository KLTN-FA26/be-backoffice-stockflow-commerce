package com.stockflow.product.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** The purchasing information of one SKU's inventory item; {@code version} is the item's. */
public record SkuPurchasingResponse(
        UUID variantId,
        String sku,
        long version,
        UUID defaultSupplierId,
        BigDecimal referencePurchasePrice,
        String referencePurchaseCurrency,
        Integer leadTimeDays,
        Integer moq
) {
}

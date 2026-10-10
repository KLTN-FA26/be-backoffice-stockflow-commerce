package com.stockflow.product.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** One variant: a sellable SKU of a product. {@code variantId} is the {@code skuId} of the SKU routes. */
public record VariantResponse(UUID variantId, UUID productId, String sku, String name, String status,
                              boolean defaultVariant, String attributeSignature, int position, Instant obsoletedAt,
                              long version) {
}

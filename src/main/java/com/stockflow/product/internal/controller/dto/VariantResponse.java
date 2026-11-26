package com.stockflow.product.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One variant: a sellable SKU of a product. {@code inventoryItemId} is the item the SKU's stock and policy live under, so a screen can go from the variant to
 * {@code /api/v1/inventory/items/{id}}; null only when the SKU has no item. {@code rotatable}: whether a carton of the
 * variant may be turned, for every SKU packed in it (owner decision Q9).

 */
public record VariantResponse(UUID variantId, UUID productId, String sku, String name, String status,
                              boolean defaultVariant, String attributeSignature, int position, Instant obsoletedAt,
                              long version, List<VariantOptionResponse> options, UUID inventoryItemId,
                              boolean rotatable) {
}

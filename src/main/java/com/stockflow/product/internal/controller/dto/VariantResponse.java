package com.stockflow.product.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One catalogue variant (quy cách) of a product. {@code rotatable}: whether a carton of the variant may be turned,
 * for every SKU packed in it (owner decision Q9). It carries no inventory item id any more: a variant is never stocked
 * and has no item (V20270105000100) - only a customer's SKU an order named has one.
 */
public record VariantResponse(UUID variantId, UUID productId, String sku, String name, String status,
                              boolean defaultVariant, String attributeSignature, int position, Instant obsoletedAt,
                              long version, List<VariantOptionResponse> options, boolean rotatable) {
}

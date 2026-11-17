package com.stockflow.catalog.internal.controller.dto;
import java.util.List;
import java.util.UUID;
/**
 * A product page. Its variants (quy cách) carry neither a price nor a stock indicator (kltn-docs 13 BR-04, BR-11): a
 * customer looks at them and designs on them; the indicator is for the customer's own SKUs
 * ({@code GET /api/v1/catalog/products/availability}).
 */
public record CatalogProductResponse(UUID productId,String slug,String title,String description,
        String seoTitle,String seoDescription,List<VariantResponse> variants,String galleryUrl,
        com.stockflow.product.api.ProductKind kind) {
    public record VariantResponse(String sku) {}
}

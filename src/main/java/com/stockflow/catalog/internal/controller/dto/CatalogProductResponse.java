package com.stockflow.catalog.internal.controller.dto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
public record CatalogProductResponse(UUID productId,String slug,String title,String description,
        String seoTitle,String seoDescription,List<VariantResponse> variants,String galleryUrl) {
    public record VariantResponse(String sku,BigDecimal price,String currency,String availability) {}
}

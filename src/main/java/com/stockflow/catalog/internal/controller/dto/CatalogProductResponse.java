package com.stockflow.catalog.internal.controller.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A product page. Its variants (quy cách) carry neither a price nor a stock indicator (kltn-docs 13 BR-04, BR-11): a
 * customer looks at them and designs on them; the indicator is for the customer's own SKUs
 * ({@code GET /api/v1/catalog/products/availability}).
 *
 * <p>{@code description} and each variant's {@code descriptionHtml} are HTML the server cleaned to its allow-list
 * (ADR-0013); a storefront still passes them through its own sanitizer before it renders them.</p>
 *
 * @param brand      null when the product has none
 * @param category   the primary category; null when none
 * @param attributes the descriptive attributes, in their order
 */
public record CatalogProductResponse(UUID productId, String slug, String title, String description,
        String seoTitle, String seoDescription, Brand brand, Category category, List<Attribute> attributes,
        List<VariantResponse> variants, String galleryUrl, com.stockflow.product.api.ProductKind kind) {

    public record Brand(UUID id, String name, String slug, String logoUrl) {}

    public record Category(UUID id, String name, String slug) {}

    /**
     * One descriptive attribute: {@code values} (labels) for a choice, else {@code text}, {@code number} (in
     * {@code unit}) or {@code bool}.
     */
    public record Attribute(String code, String name, String dataType, String unit, List<String> values,
                            String text, BigDecimal number, Boolean bool) {}

    /**
     * @param name            the variant's name, the same as the list card's {@code CardSku.name}
     * @param descriptionHtml the variant's own description, else the product's
     * @param ownDescription  whether {@code descriptionHtml} is the variant's own
     * @param options         its axis values, e.g. "Dung tích: 12oz"
     */
    public record VariantResponse(UUID variantId, String sku, String name, String descriptionHtml,
                                  boolean ownDescription, List<Option> options) {}

    public record Option(String axisCode, String axisName, String valueCode, String label) {}
}

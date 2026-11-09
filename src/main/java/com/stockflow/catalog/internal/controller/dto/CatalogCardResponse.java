package com.stockflow.catalog.internal.controller.dto;

import com.stockflow.product.api.ProductKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * One row of the storefront catalogue list. {@code primaryImageUrl} and {@code skus} are optional
 * additions that spare a card its detail and gallery calls; the row carries no price - the storefront shows none
 * (kltn-docs 13 BR-04) - and no stock (a customer's own SKUs have the CUSTOMER-only {@code /availability} indicator).
 */
public record CatalogCardResponse(
        UUID productId,
        String slug,
        String title,
        String seoTitle,
        @Schema(description = "Public URL of the primary published image, of the kind the gallery returns; absent without one")
        String primaryImageUrl,
        @Schema(description = "The product's ACTIVE variants, in display order") List<CardSku> skus,
        @Schema(description = "STANDARD, or CUSTOMIZABLE for a product printed to the customer's design: quoted by "
                + "Sales, not added to a basket") ProductKind kind) {

    /** A sellable variant: its SKU code and name. */
    public record CardSku(String sku, String name) {}
}

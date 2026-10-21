package com.stockflow.product.api;

import java.util.List;

/**
 * What a storefront list card needs of one published product, read for a whole page in one go
 * ({@link ProductPublication#cards}); the card then needs no detail or gallery call of its own.
 *
 * @param primaryImageUrl the same public URL kind the gallery returns for the image: the product's
 *                        primary published image, else its first one; null when it has none
 * @param skus            the product's ACTIVE variants, in display order (the ones the detail page sells)
 */
public record PublishedProductCard(String primaryImageUrl, List<Sku> skus) {

    /** One sellable variant: its SKU code and its name (never a price or a stock figure). */
    public record Sku(String sku, String name) {}
}

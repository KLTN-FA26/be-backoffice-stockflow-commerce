package com.stockflow.product.api;
import java.util.List;
import java.util.UUID;
/** Approved master data only; never contains private media keys or review metadata. */
public record PublicationProduct(UUID productId, String name, String description, UUID categoryId,
        ProductStatus status, List<PublicationSku> skus, ProductKind kind) {
    /** One ACTIVE variant: its id, SKU code and name, the same name a storefront card shows for it. */
    public record PublicationSku(UUID skuId, String sku, String name) {}
}

package com.stockflow.product.api;
import java.util.List;
import java.util.UUID;
/** Approved master data only; never contains private media keys or review metadata. */
public record PublicationProduct(UUID productId, String name, String description, UUID categoryId,
        ProductStatus status, List<PublicationSku> skus) {
    public record PublicationSku(UUID skuId, String sku) {}
}

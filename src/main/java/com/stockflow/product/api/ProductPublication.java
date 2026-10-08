package com.stockflow.product.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProductPublication {
    PublicationProduct lock(UUID productId);

    PublicationProduct read(UUID productId);

    Set<UUID> published(Set<UUID> productIds);

    /** Keyset page of canonical publications, including products with no catalog snapshot yet. */
    List<UUID> publishedPage(UUID after, int size);

    PublicationProduct.PublicationSku inventorySku(UUID productId, UUID variantId);

    List<PublishedProductImage> publishedImages(UUID productId);

    ProductEcommerce ecommerce(UUID productId);

    void editEcommerce(UUID productId, String slug, String seoTitle, String seoDescription);

    void advanceCommerceRevision(UUID productId);

    void publish(UUID productId);

    void unpublish(UUID productId);
}

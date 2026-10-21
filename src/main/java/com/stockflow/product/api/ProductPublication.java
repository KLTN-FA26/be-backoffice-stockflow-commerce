package com.stockflow.product.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ProductPublication {
    PublicationProduct lock(UUID productId);

    PublicationProduct read(UUID productId);

    Set<UUID> published(Set<UUID> productIds);

    /** Keyset page of canonical publications, including products with no catalog snapshot yet. */
    List<UUID> publishedPage(UUID after, int size);

    List<PublishedProductImage> publishedImages(UUID productId);

    /**
     * The list-card data of many published products, in two queries whatever the number asked for: the
     * primary image URL and the ACTIVE variants of each. A product that is not published, or unknown, has no
     * entry. Never a price or a stock figure.
     */
    Map<UUID, PublishedProductCard> cards(Set<UUID> productIds);

    ProductEcommerce ecommerce(UUID productId);

    void editEcommerce(UUID productId, String slug, String seoTitle, String seoDescription);

    void advanceCommerceRevision(UUID productId);

    void publish(UUID productId);

    void unpublish(UUID productId);
}

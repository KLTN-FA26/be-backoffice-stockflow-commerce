package com.stockflow.product.api;
import java.util.UUID;
import java.util.Set;
public interface ProductPublication {
    PublicationProduct lock(UUID productId);
    PublicationProduct read(UUID productId);
    Set<UUID> published(Set<UUID> productIds);
    void publish(UUID productId);
    void unpublish(UUID productId);
}

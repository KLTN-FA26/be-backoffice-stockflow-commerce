package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port of the {@link Product} aggregate. {@code save} also writes the product's primary
 * category ({@code product.product_categories}); the reference checks below exist so the service
 * can answer 404 with a real error code before the foreign key would answer 500.
 */
public interface ProductRepository extends AggregateRepository<Product, ProductId> {

    Optional<Product> findForUpdate(ProductId id);

    /** Whether {@code sku} is a variant of this product. */
    boolean containsSku(UUID productId, String sku);

    /** The name of the product whose variant {@code sku} is. */
    Optional<String> nameForSku(String sku);

    boolean existsByCode(String code);

    /** Whether a variant anywhere already carries {@code sku}: the default variant takes the code. */
    boolean skuExists(String sku);

    boolean categoryExists(UUID categoryId);

    boolean brandExists(UUID brandId);
}

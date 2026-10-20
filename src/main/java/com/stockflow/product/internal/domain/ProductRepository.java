package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.List;
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

    /** Whether a variant anywhere already carries {@code sku}: the default variant of a product created
     *  without axes takes the code. */
    boolean skuExists(String sku);

    boolean categoryExists(UUID categoryId);

    boolean brandExists(UUID brandId);

    /** The SKU of every variant of the product, whatever its status. */
    List<String> skusOf(ProductId id);

    /**
     * Deletes the product with its variants (SCRUM-461). What the variants own in other modules — the
     * inventory item of each SKU — must be gone first, or the foreign keys refuse. Images and
     * attributes go with the rows through {@code ON DELETE CASCADE}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCT_IN_USE} when a design, a storefront
     *         listing, an order or a cart line still points at the product or one of its variants
     */
    void delete(ProductId id);
}

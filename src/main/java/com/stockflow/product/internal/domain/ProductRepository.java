package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port of the {@link Product} aggregate. {@code save} also writes the product's primary
 * category ({@code product.product_categories}); the reference checks below exist so the service
 * can answer 404 with a real error code before the foreign key would answer 500, and 409 for a brand or
 * category retired by deactivation (SCRUM-87).
 */
public interface ProductRepository extends AggregateRepository<Product, ProductId> {

    Optional<Product> findForUpdate(ProductId id);

    /** Whether {@code sku} is a variant or a customer's own SKU of this product. */
    boolean containsSku(UUID productId, String sku);

    /** The name of the product whose variant or customer's own SKU {@code sku} is. */
    Optional<String> nameForSku(String sku);

    /** See {@link com.stockflow.product.api.ProductService#taxClassesOf}. */
    java.util.Map<String, com.stockflow.product.api.TaxClass> taxClassesOf(java.util.Collection<String> skus);

    /** WF-05 D-8: the product of each of {@code skus} that is a variant SKU, with its tax class and version. */
    List<ProductTaxClass> taxClassesBySku(java.util.Collection<String> skus);

    /** WF-05 D-8: the product's tax class and version as stored now. */
    Optional<ProductTaxClass> taxClassOf(ProductId id);

    /** See {@link com.stockflow.product.api.ProductService#variantNamesOf}. */
    java.util.Map<String, String> variantNamesOf(java.util.Collection<String> skus);

    boolean existsByCode(String code);

    /** Whether a variant or a customer's own SKU anywhere already carries {@code sku}: the default variant of a
     *  product created without axes takes the code, and a code names one thing (tg_variants_code_free). */
    boolean skuExists(String sku);

    ReferenceStatus categoryStatus(UUID categoryId);

    ReferenceStatus brandStatus(UUID brandId);

    /** The SKU of every variant of the product, then of every customer's own SKU of it, whatever its status. */
    List<String> skusOf(ProductId id);

    /**
     * Deletes the product with its variants (SCRUM-461). Anything in other modules that names a variant or
     * a customer's SKU of the product refuses through its foreign key (a variant has no inventory item since
     * V20270105000100; its cartons cascade). Images and attributes go with the rows through
     * {@code ON DELETE CASCADE}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCT_IN_USE} when a design, a storefront
     *         listing, an order or a cart line still points at the product or one of its variants
     */
    void delete(ProductId id);
}

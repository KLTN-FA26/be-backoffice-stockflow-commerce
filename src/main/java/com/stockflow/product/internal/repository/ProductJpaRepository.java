package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Products, and the SKU-keyed reads of them. A SKU is a catalogue variant's ({@code product.variants}) or a customer's
 * own ({@code product.customer_skus}, V20270105000100) - a code is in exactly one of the two tables - and quotes,
 * orders, designs and reports name the customer's: every SKU-keyed read here has a query per table, and the adapter
 * asks both.
 */
public interface ProductJpaRepository extends BaseJpaRepository<ProductJpaEntity> {

    /** Whether {@code sku} is one of this product's variants — any status: an order or a design made
     *  while the variant was active still belongs to the product after it is blocked. */
    @Query("select (count(v) > 0) from VariantJpaEntity v where v.productId = :productId and v.sku = :sku")
    boolean containsSku(@Param("productId") UUID productId, @Param("sku") String sku);

    /** {@link #containsSku} for a customer's own SKU of the product, any status. */
    @Query("select (count(c) > 0) from CustomerSkuJpaEntity c where c.productId = :productId and c.sku = :sku")
    boolean containsCustomerSku(@Param("productId") UUID productId, @Param("sku") String sku);

    @Query("select p.name from ProductJpaEntity p, VariantJpaEntity v "
            + "where v.productId = p.id and upper(v.sku) = upper(:sku)")
    List<String> namesForSku(@Param("sku") String sku);

    /** {@link #namesForSku} for a customer's own SKU. */
    @Query("select p.name from ProductJpaEntity p, CustomerSkuJpaEntity c "
            + "where c.productId = p.id and upper(c.sku) = upper(:sku)")
    List<String> namesForCustomerSku(@Param("sku") String sku);

    /** The SKU (as stored) and its product's tax class, for each of {@code skus} whose product has one. */
    @Query("select v.sku, p.taxClass from ProductJpaEntity p, VariantJpaEntity v "
            + "where v.productId = p.id and v.sku in :skus and p.taxClass is not null")
    List<Object[]> taxClassesOf(@Param("skus") java.util.Collection<String> skus);

    /** {@link #taxClassesOf} for customers' own SKUs: a line's VAT follows its SKU's product (14 BR-21). */
    @Query("select c.sku, p.taxClass from ProductJpaEntity p, CustomerSkuJpaEntity c "
            + "where c.productId = p.id and c.sku in :skus and p.taxClass is not null")
    List<Object[]> customerSkuTaxClassesOf(@Param("skus") java.util.Collection<String> skus);

    /** WF-05 D-8: SKU, product id, code, name, status, tax class and version, for each of {@code skus}. */
    @Query("select v.sku, p.id, p.code, p.name, p.status, p.taxClass, p.version from ProductJpaEntity p, "
            + "VariantJpaEntity v where v.productId = p.id and v.sku in :skus order by v.sku")
    List<Object[]> taxClassesBySku(@Param("skus") java.util.Collection<String> skus);

    /** {@link #taxClassesBySku} for customers' own SKUs. */
    @Query("select c.sku, p.id, p.code, p.name, p.status, p.taxClass, p.version from ProductJpaEntity p, "
            + "CustomerSkuJpaEntity c where c.productId = p.id and c.sku in :skus order by c.sku")
    List<Object[]> customerSkuTaxClassesBySku(@Param("skus") java.util.Collection<String> skus);

    /** WF-05 D-8: id, code, name, status, tax class and version of one product. */
    @Query("select p.id, p.code, p.name, p.status, p.taxClass, p.version from ProductJpaEntity p where p.id = :id")
    List<Object[]> taxClassOf(@Param("id") UUID id);

    /** The SKU (as stored) and its variant's name, for each of {@code skus}. */
    @Query("select v.sku, v.name from VariantJpaEntity v where v.sku in :skus")
    List<Object[]> variantNamesOf(@Param("skus") java.util.Collection<String> skus);

    /** The SKU (as stored) and its own name, for each of {@code skus} that is a customer's own SKU. */
    @Query("select c.sku, c.name from CustomerSkuJpaEntity c where c.sku in :skus")
    List<Object[]> customerSkuNamesOf(@Param("skus") java.util.Collection<String> skus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id = :id")
    Optional<ProductJpaEntity> lockById(@Param("id") UUID id);

    boolean existsByCode(String code);

    boolean existsBySlug(String slug);
}

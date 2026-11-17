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

/** Spring Data repository for {@link ProductJpaEntity}. */
public interface ProductJpaRepository extends BaseJpaRepository<ProductJpaEntity> {

    /** Whether {@code sku} is one of this product's variants — any status: an order or a design made
     *  while the variant was active still belongs to the product after it is blocked. */
    @Query("select (count(v) > 0) from VariantJpaEntity v where v.productId = :productId and v.sku = :sku")
    boolean containsSku(@Param("productId") UUID productId, @Param("sku") String sku);

    @Query("select p.name from ProductJpaEntity p, VariantJpaEntity v "
            + "where v.productId = p.id and upper(v.sku) = upper(:sku)")
    List<String> namesForSku(@Param("sku") String sku);

    /** The SKU (as stored) and its product's tax class, for each of {@code skus} whose product has one. */
    @Query("select v.sku, p.taxClass from ProductJpaEntity p, VariantJpaEntity v "
            + "where v.productId = p.id and v.sku in :skus and p.taxClass is not null")
    List<Object[]> taxClassesOf(@Param("skus") java.util.Collection<String> skus);

    /** The SKU (as stored) and its variant's name, for each of {@code skus}. */
    @Query("select v.sku, v.name from VariantJpaEntity v where v.sku in :skus")
    List<Object[]> variantNamesOf(@Param("skus") java.util.Collection<String> skus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id = :id")
    Optional<ProductJpaEntity> lockById(@Param("id") UUID id);

    boolean existsByCode(String code);

    boolean existsBySlug(String slug);
}

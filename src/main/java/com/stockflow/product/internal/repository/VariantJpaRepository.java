package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VariantJpaRepository extends BaseJpaRepository<VariantJpaEntity> {

    Optional<VariantJpaEntity> findByIdAndProductId(UUID id, UUID productId);

    Optional<VariantJpaEntity> findByProductIdAndDefaultVariantTrue(UUID productId);

    Optional<VariantJpaEntity> findBySku(String sku);

    List<VariantJpaEntity> findBySkuIn(java.util.Collection<String> skus);

    /** {@code [sku, name]} of each variant among {@code skus}: a projection, so a label sheet loads no entity. */
    @Query("select v.sku, v.name from VariantJpaEntity v where v.sku in :skus")
    List<Object[]> namesBySku(@Param("skus") java.util.Collection<String> skus);

    /** {@code [sku, id]} of each variant among {@code skus}: a projection, for inventory's carton lookups. */
    @Query("select v.sku, v.id from VariantJpaEntity v where v.sku in :skus")
    List<Object[]> idsBySku(@Param("skus") java.util.Collection<String> skus);

    /** {@code [id, rotatable]} of each variant among {@code ids}: a projection (owner decision Q9). */
    @Query("select v.id, v.rotatable from VariantJpaEntity v where v.id in :ids")
    List<Object[]> rotatableById(@Param("ids") java.util.Collection<java.util.UUID> ids);

    /** {@code [sku, product name, variant name]} of each variant among {@code skus}, in one query. */
    @Query("""
            select v.sku, p.name, v.name from VariantJpaEntity v, ProductJpaEntity p
             where p.id = v.productId and v.sku in :skus""")
    List<Object[]> itemNamesBySku(@Param("skus") java.util.Collection<String> skus);

    List<VariantJpaEntity> findByProductIdAndStatus(UUID productId, VariantStatus status);

    List<VariantJpaEntity> findByProductIdOrderByPositionAscIdAsc(UUID productId);

    boolean existsBySku(String sku);

    boolean existsBySkuAndStatus(String sku, VariantStatus status);

    boolean existsByProductIdAndAttributeSignature(UUID productId, String attributeSignature);

    @Query("select coalesce(max(v.position), -1) + 1 from VariantJpaEntity v where v.productId = :productId")
    int nextPosition(@Param("productId") UUID productId);
}

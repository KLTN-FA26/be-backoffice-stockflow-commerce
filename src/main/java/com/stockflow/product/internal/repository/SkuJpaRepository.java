package com.stockflow.product.internal.repository;

import com.stockflow.product.internal.entity.SkuJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link SkuJpaEntity}. STARTER STUB — CRUD + Specification from the base. */
public interface SkuJpaRepository extends BaseJpaRepository<SkuJpaEntity> {

    java.util.Optional<SkuJpaEntity> findByCode(String code);
    @org.springframework.data.jpa.repository.Query("select s from SkuJpaEntity s, VariantJpaEntity v where s.variantId=v.id and v.productId=:productId order by s.code")
    java.util.List<SkuJpaEntity> findForProduct(@org.springframework.data.repository.query.Param("productId") java.util.UUID productId);
}

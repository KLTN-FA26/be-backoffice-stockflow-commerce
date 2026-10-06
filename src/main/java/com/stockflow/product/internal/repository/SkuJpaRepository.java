package com.stockflow.product.internal.repository;

import com.stockflow.product.internal.entity.SkuJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link SkuJpaEntity}. STARTER STUB — CRUD + Specification from the base. */
public interface SkuJpaRepository extends BaseJpaRepository<SkuJpaEntity> {

    java.util.Optional<SkuJpaEntity> findByCode(String code);
}

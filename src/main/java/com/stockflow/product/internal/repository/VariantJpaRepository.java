package com.stockflow.product.internal.repository;

import com.stockflow.product.internal.entity.VariantJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link VariantJpaEntity}. STARTER STUB — CRUD + Specification from the base. */
public interface VariantJpaRepository extends BaseJpaRepository<VariantJpaEntity> {

    boolean existsByIdAndProductId(java.util.UUID id, java.util.UUID productId);
}

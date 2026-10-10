package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.BrandJpaEntity;

public interface BrandJpaRepository extends BaseJpaRepository<BrandJpaEntity> {

    boolean existsByCode(String code);

    boolean existsBySlug(String slug);
}

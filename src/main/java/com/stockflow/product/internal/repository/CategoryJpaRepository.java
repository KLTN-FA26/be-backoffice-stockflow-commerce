package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.CategoryJpaEntity;

public interface CategoryJpaRepository extends BaseJpaRepository<CategoryJpaEntity> {

    boolean existsByCode(String code);

    boolean existsBySlug(String slug);
}

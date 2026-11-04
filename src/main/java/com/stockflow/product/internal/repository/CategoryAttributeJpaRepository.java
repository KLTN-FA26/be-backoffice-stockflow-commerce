package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.CategoryAttributeJpaEntity;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link CategoryAttributeJpaEntity}. */
public interface CategoryAttributeJpaRepository extends BaseJpaRepository<CategoryAttributeJpaEntity> {

    List<CategoryAttributeJpaEntity> findByCategoryId(UUID categoryId);
}

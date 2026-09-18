package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.entity.AreaJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link AreaJpaEntity}. Finders arrive with the use cases (issue #24). */
interface AreaJpaRepository extends BaseJpaRepository<AreaJpaEntity> {
}

package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.entity.BoundaryJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link BoundaryJpaEntity}. Finders arrive with the use cases (issue #24). */
interface BoundaryJpaRepository extends BaseJpaRepository<BoundaryJpaEntity> {
}

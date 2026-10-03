package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.entity.ZoneJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link ZoneJpaEntity}. Finders arrive with the use cases (issue #21). */
interface ZoneJpaRepository extends BaseJpaRepository<ZoneJpaEntity> {
}

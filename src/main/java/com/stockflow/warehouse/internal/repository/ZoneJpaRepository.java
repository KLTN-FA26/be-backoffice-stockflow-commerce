package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.entity.ZoneJpaEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link ZoneJpaEntity}. */
interface ZoneJpaRepository extends BaseJpaRepository<ZoneJpaEntity> {

    Optional<ZoneJpaEntity> findByWarehouseIdAndName(UUID warehouseId, String name);

    List<ZoneJpaEntity> findByWarehouseIdOrderByNameAsc(UUID warehouseId);
}

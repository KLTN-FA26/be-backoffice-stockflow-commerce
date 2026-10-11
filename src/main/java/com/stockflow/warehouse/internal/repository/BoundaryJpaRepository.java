package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.entity.BoundaryJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link BoundaryJpaEntity}. */
interface BoundaryJpaRepository extends BaseJpaRepository<BoundaryJpaEntity> {

    @Query("select b.warehouseId from BoundaryJpaEntity b where b.id = :id")
    Optional<UUID> findWarehouseIdById(@Param("id") UUID id);
}

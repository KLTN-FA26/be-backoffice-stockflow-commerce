package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.entity.ZoneJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link ZoneJpaEntity}. */
interface ZoneJpaRepository extends BaseJpaRepository<ZoneJpaEntity> {

    List<ZoneJpaEntity> findByWarehouseIdOrderByNameAsc(UUID warehouseId);

    @Query("select z.warehouseId from ZoneJpaEntity z where z.id = :id")
    Optional<UUID> findWarehouseIdById(@Param("id") UUID id);
}

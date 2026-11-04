package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.entity.AreaJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link AreaJpaEntity}, the root of the Area aggregate. */
interface AreaJpaRepository extends BaseJpaRepository<AreaJpaEntity> {

    /** The area with its storage location in one query; {@code left}, as a NON_STORAGE area has none. */
    @Query("""
            select a from AreaJpaEntity a
            left join fetch a.location
            where a.id = :id
            """)
    Optional<AreaJpaEntity> findWithLocationById(@Param("id") UUID id);

    @Query("select a.warehouseId from AreaJpaEntity a where a.id = :id")
    Optional<UUID> findWarehouseIdById(@Param("id") UUID id);

    boolean existsByWarehouseIdAndCode(UUID warehouseId, String code);
}

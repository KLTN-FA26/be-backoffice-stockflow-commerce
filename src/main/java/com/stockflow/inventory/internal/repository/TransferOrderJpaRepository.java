package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.entity.TransferOrderJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface TransferOrderJpaRepository extends BaseJpaRepository<TransferOrderJpaEntity> {

    @Query("select distinct t from TransferOrderJpaEntity t left join fetch t.lines where t.id = :id")
    Optional<TransferOrderJpaEntity> findByIdWithLines(@Param("id") UUID id);

    /** SCRUM-287 PR T: several transfers with their lines, in one query. */
    @Query("select distinct t from TransferOrderJpaEntity t left join fetch t.lines where t.id in :ids")
    java.util.List<TransferOrderJpaEntity> findAllWithLines(@Param("ids") java.util.Collection<UUID> ids);

    /** SCRUM-287 PR T: packed full truckloads still being picked at the warehouse, with their lines. */
    @Query("""
            select distinct t from TransferOrderJpaEntity t left join fetch t.lines
            where t.fromWarehouseId = :warehouseId
              and t.status = com.stockflow.inventory.internal.domain.TransferStatus.PICKING
              and t.packedAt is not null
              and t.deliveryMode = com.stockflow.inventory.api.TransferDeliveryMode.FULL_TRUCKLOAD
            """)
    java.util.List<TransferOrderJpaEntity> findAwaitingDispatch(@Param("warehouseId") UUID warehouseId);
}

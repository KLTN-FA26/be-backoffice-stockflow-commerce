package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Load headers (G4-2a). Every write to a load takes its row lock first, so two dispatchers on one load queue up. */
public interface DispatchLoadJpaRepository extends BaseJpaRepository<DispatchLoadJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from DispatchLoadJpaEntity l where l.id = :id")
    Optional<DispatchLoadJpaEntity> lockById(@Param("id") UUID id);

    Optional<DispatchLoadJpaEntity> findByRequestId(UUID requestId);

    /** What a load is, read without loading the entity (so a lock taken next reads it fresh). */
    interface LoadKey {
        UUID getWarehouseId();

        UUID getLoadPlanVehicleId();
    }

    @Query("select l.warehouseId as warehouseId, l.loadPlanVehicleId as loadPlanVehicleId "
            + "from DispatchLoadJpaEntity l where l.id = :id")
    Optional<LoadKey> findKeyById(@Param("id") UUID id);

    List<DispatchLoadJpaEntity> findByIdIn(Collection<UUID> ids);

    /** The loads opened for these load-plan vehicles (SCRUM-287 PR D), cancelled ones included. */
    List<DispatchLoadJpaEntity> findByLoadPlanVehicleIdIn(Collection<UUID> loadPlanVehicleIds);
}

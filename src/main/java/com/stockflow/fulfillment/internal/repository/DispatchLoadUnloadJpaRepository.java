package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DispatchLoadUnloadJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Parcels to take off vehicles: of cancelled orders (SCRUM-392), of vehicles called off under control (SCRUM-474). */
public interface DispatchLoadUnloadJpaRepository extends BaseJpaRepository<DispatchLoadUnloadJpaEntity> {

    List<DispatchLoadUnloadJpaEntity> findByLoadIdOrderByRequestedAtAscIdAsc(UUID loadId);

    /** The parcels' unloads still pending - the lock of UNLOAD-DESIGN point 4. */
    List<DispatchLoadUnloadJpaEntity> findByPackageIdInAndUnloadedAtIsNull(Collection<UUID> packageIds);

    List<DispatchLoadUnloadJpaEntity> findByLoadIdInAndUnloadedAtIsNull(Collection<UUID> loadIds);
}

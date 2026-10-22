package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DispatchLoadUnloadJpaEntity;

import java.util.List;
import java.util.UUID;

/** Parcels of cancelled orders to take off vehicles being loaded (SCRUM-392). */
public interface DispatchLoadUnloadJpaRepository extends BaseJpaRepository<DispatchLoadUnloadJpaEntity> {

    List<DispatchLoadUnloadJpaEntity> findByLoadIdOrderByRequestedAtAscIdAsc(UUID loadId);
}

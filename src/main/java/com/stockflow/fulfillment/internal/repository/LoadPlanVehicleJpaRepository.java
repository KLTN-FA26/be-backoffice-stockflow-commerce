package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadPlanVehicleJpaEntity;

import java.util.List;
import java.util.UUID;

public interface LoadPlanVehicleJpaRepository extends BaseJpaRepository<LoadPlanVehicleJpaEntity> {

    List<LoadPlanVehicleJpaEntity> findByLoadPlanIdOrderBySeqAscCreatedAtAsc(UUID loadPlanId);
}

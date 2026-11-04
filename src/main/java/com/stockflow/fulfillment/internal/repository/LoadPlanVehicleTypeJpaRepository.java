package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadPlanVehicleTypeJpaEntity;

import java.util.List;
import java.util.UUID;

public interface LoadPlanVehicleTypeJpaRepository extends BaseJpaRepository<LoadPlanVehicleTypeJpaEntity> {

    List<LoadPlanVehicleTypeJpaEntity> findByLoadPlanIdOrderBySortOrderAsc(UUID loadPlanId);
}

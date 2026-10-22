package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadPlacementJpaEntity;

import java.util.List;
import java.util.UUID;

public interface LoadPlacementJpaRepository extends BaseJpaRepository<LoadPlacementJpaEntity> {

    List<LoadPlacementJpaEntity> findByLoadPlanId(UUID loadPlanId);
}

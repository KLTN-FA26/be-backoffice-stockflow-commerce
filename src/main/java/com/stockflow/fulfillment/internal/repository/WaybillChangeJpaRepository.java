package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.WaybillChangeJpaEntity;

import java.util.List;
import java.util.UUID;

/** Every change of a load's waybill number (3-2). */
public interface WaybillChangeJpaRepository extends BaseJpaRepository<WaybillChangeJpaEntity> {

    List<WaybillChangeJpaEntity> findByLoadIdOrderByChangedAtAscIdAsc(UUID loadId);
}

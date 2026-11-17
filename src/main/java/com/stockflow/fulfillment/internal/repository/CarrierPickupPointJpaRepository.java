package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierPickupPointJpaEntity;

import java.util.List;
import java.util.UUID;

/** Where API_BOOKING carriers pick up at each warehouse (3-4). */
public interface CarrierPickupPointJpaRepository extends BaseJpaRepository<CarrierPickupPointJpaEntity> {

    List<CarrierPickupPointJpaEntity> findByCarrierIdOrderByWarehouseIdAsc(UUID carrierId);
}

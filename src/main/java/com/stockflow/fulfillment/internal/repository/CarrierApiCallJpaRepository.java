package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierApiCallJpaEntity;

import java.util.List;
import java.util.UUID;

/** Every call to a carrier's API (3-4). */
public interface CarrierApiCallJpaRepository extends BaseJpaRepository<CarrierApiCallJpaEntity> {

    List<CarrierApiCallJpaEntity> findByBookingIdOrderByCalledAtAscIdAsc(UUID bookingId);
}

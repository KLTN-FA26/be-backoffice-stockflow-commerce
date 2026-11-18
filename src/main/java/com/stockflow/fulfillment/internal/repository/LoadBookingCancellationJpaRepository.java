package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadBookingCancellationJpaEntity;

import java.util.List;
import java.util.UUID;

/** The bookings taken off each load (09 §4.2 step 6, RTM 09A-125). */
public interface LoadBookingCancellationJpaRepository extends BaseJpaRepository<LoadBookingCancellationJpaEntity> {

    List<LoadBookingCancellationJpaEntity> findByLoadIdOrderByCancelledAtAscIdAsc(UUID loadId);
}

package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadBookingCancellationJpaEntity;

/** The bookings taken off each load (09 §4.2 step 6, RTM 09A-125). */
public interface LoadBookingCancellationJpaRepository extends BaseJpaRepository<LoadBookingCancellationJpaEntity> {
}

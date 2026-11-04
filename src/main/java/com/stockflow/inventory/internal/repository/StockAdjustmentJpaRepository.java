package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import com.stockflow.inventory.internal.entity.StockAdjustmentJpaEntity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface StockAdjustmentJpaRepository extends BaseJpaRepository<StockAdjustmentJpaEntity> {

    Optional<StockAdjustmentJpaEntity> findFirstByReservationIdOrderByCreatedAtDescIdDesc(UUID reservationId);

    /** uk_stock_adjustment_live_reservation: at most one. */
    Optional<StockAdjustmentJpaEntity> findFirstByReservationIdAndStatusIn(UUID reservationId,
                                                                           Collection<AdjustmentStatus> statuses);
}

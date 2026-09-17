package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.OrderHoldJpaEntity;
import java.util.Optional;
import java.util.UUID;

public interface OrderHoldJpaRepository extends BaseJpaRepository<OrderHoldJpaEntity> {
    Optional<OrderHoldJpaEntity> findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(UUID orderId);
}

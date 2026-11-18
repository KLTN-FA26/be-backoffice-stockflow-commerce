package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.OrderCompletionHoldJpaEntity;

import java.util.Optional;
import java.util.UUID;

/** WF-05 GH-2: an order's completion holds. At most one open per order ({@code uk_order_completion_hold_open}). */
public interface OrderCompletionHoldJpaRepository extends BaseJpaRepository<OrderCompletionHoldJpaEntity> {

    Optional<OrderCompletionHoldJpaEntity> findFirstByOrderIdAndReleasedAtIsNull(UUID orderId);
}

package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.OrderStatusHistoryJpaEntity;

import java.util.List;
import java.util.UUID;

interface OrderStatusHistoryJpaRepository extends BaseJpaRepository<OrderStatusHistoryJpaEntity> {

    List<OrderStatusHistoryJpaEntity> findByOrderIdOrderByOccurredAtAscCreatedAtAsc(UUID orderId);
}

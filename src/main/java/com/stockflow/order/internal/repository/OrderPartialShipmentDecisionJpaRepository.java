package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.OrderPartialShipmentDecisionJpaEntity;

import java.util.List;
import java.util.UUID;

public interface OrderPartialShipmentDecisionJpaRepository
        extends BaseJpaRepository<OrderPartialShipmentDecisionJpaEntity> {

    List<OrderPartialShipmentDecisionJpaEntity> findByOrderIdOrderByDecidedAtAscIdAsc(UUID orderId);
}

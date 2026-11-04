package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.OrderDeliveryModeChangeJpaEntity;

import java.util.List;
import java.util.UUID;

public interface OrderDeliveryModeChangeJpaRepository extends BaseJpaRepository<OrderDeliveryModeChangeJpaEntity> {

    List<OrderDeliveryModeChangeJpaEntity> findByOrderIdOrderByChangedAtAscIdAsc(UUID orderId);
}

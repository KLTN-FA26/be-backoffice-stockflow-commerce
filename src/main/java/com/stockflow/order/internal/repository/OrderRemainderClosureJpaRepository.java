package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.OrderRemainderClosureJpaEntity;

import java.util.List;
import java.util.UUID;

public interface OrderRemainderClosureJpaRepository extends BaseJpaRepository<OrderRemainderClosureJpaEntity> {

    List<OrderRemainderClosureJpaEntity> findByOrderIdOrderByClosedAtAscIdAsc(UUID orderId);
}

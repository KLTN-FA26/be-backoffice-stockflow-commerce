package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.OrderAddressChangeJpaEntity;

import java.util.List;
import java.util.UUID;

/** The changes of an order's delivery address (WF04 G2), always read for one order, oldest first. */
public interface OrderAddressChangeJpaRepository extends BaseJpaRepository<OrderAddressChangeJpaEntity> {

    List<OrderAddressChangeJpaEntity> findByOrderIdOrderByChangedAtAscIdAsc(UUID orderId);
}

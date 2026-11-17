package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.ProductionOrderJpaEntity;

import java.util.List;
import java.util.UUID;

interface ProductionOrderJpaRepository extends BaseJpaRepository<ProductionOrderJpaEntity> {

    List<ProductionOrderJpaEntity> findBySalesOrderIdOrderByCreatedAtAscIdAsc(UUID salesOrderId);
}

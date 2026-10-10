package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.entity.OrderCancellationRequestJpaEntity;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

interface OrderCancellationRequestJpaRepository extends BaseJpaRepository<OrderCancellationRequestJpaEntity>,
        JpaSpecificationExecutor<OrderCancellationRequestJpaEntity> {

    boolean existsByOrderIdAndStatus(UUID orderId, CancellationRequestStatus status);
}

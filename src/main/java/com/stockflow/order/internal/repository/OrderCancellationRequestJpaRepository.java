package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.entity.OrderCancellationRequestJpaEntity;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

interface OrderCancellationRequestJpaRepository extends BaseJpaRepository<OrderCancellationRequestJpaEntity>,
        JpaSpecificationExecutor<OrderCancellationRequestJpaEntity> {

    boolean existsByOrderIdAndStatus(UUID orderId, CancellationRequestStatus status);

    java.util.Optional<OrderCancellationRequestJpaEntity> findFirstByOrderIdAndStatus(UUID orderId,
                                                                                     CancellationRequestStatus status);

    java.util.Optional<OrderCancellationRequestJpaEntity> findFirstByOrderIdOrderByRequestedAtDescIdDesc(UUID orderId);

    @org.springframework.data.jpa.repository.Query("""
            select r from OrderCancellationRequestJpaEntity r
            where r.orderId in :orderIds
            order by r.requestedAt desc, r.id desc""")
    java.util.List<OrderCancellationRequestJpaEntity> findByOrderIdsNewestFirst(
            @org.springframework.data.repository.query.Param("orderIds") java.util.Collection<UUID> orderIds);
}

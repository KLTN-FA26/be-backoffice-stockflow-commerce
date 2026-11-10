package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Persistence access for task-scoped pick work. */
public interface PickJpaRepository extends BaseJpaRepository<PickJpaEntity> {

    /**
     * The order's tasks, oldest first: one per released delivery batch since 4B-8 (07 BR-07), one for an order without
     * a schedule.
     */
    @org.springframework.data.jpa.repository.Query(
            "select p from PickJpaEntity p where p.orderId = :orderId order by p.createdAt, p.id")
    java.util.List<PickJpaEntity> findAllByOrderId(@org.springframework.data.repository.query.Param("orderId")
                                                   java.util.UUID orderId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PickJpaEntity p where p.id = :id")
    java.util.Optional<PickJpaEntity> lockById(@org.springframework.data.repository.query.Param("id") java.util.UUID id);

    /**
     * The order's tasks under the same write lock as {@link #lockById}, in id order: read for the first time here,
     * never stale. One per released delivery batch since 4B-8.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PickJpaEntity p where p.orderId = :orderId order by p.id")
    java.util.List<PickJpaEntity> lockAllByOrderId(@org.springframework.data.repository.query.Param("orderId") java.util.UUID orderId);

    /** G4-2a: the order numbers of a page of shipments, in one read. */
    java.util.List<PickJpaEntity> findByOrderIdIn(java.util.Collection<java.util.UUID> orderIds);
}

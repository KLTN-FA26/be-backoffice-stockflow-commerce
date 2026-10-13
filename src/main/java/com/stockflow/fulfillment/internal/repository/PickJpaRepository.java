package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Persistence access for task-scoped pick work. */
public interface PickJpaRepository extends BaseJpaRepository<PickJpaEntity> {

    java.util.Optional<PickJpaEntity> findByOrderId(java.util.UUID orderId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PickJpaEntity p where p.id = :id")
    java.util.Optional<PickJpaEntity> lockById(@org.springframework.data.repository.query.Param("id") java.util.UUID id);
}

package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Persistence access for task-scoped pick work. */
public interface PickJpaRepository extends BaseJpaRepository<PickJpaEntity> {

    java.util.Optional<PickJpaEntity> findByOrderId(java.util.UUID orderId);
    java.util.List<PickJpaEntity> findTop100ByAssignedUserIdOrderByCreatedAtDesc(java.util.UUID assignedUserId);
    java.util.List<PickJpaEntity> findTop100ByOrderByCreatedAtDesc();
    @org.springframework.data.jpa.repository.Query("""
            select pick from PickJpaEntity pick, PackJpaEntity pack
            where pack.pickId = pick.id and pack.status = :status
            order by pick.createdAt desc
            """)
    java.util.List<PickJpaEntity> findByPackStatus(
            @org.springframework.data.repository.query.Param("status")
            com.stockflow.fulfillment.internal.domain.PackStatus status,
            org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PickJpaEntity p where p.id = :id")
    java.util.Optional<PickJpaEntity> lockById(@org.springframework.data.repository.query.Param("id") java.util.UUID id);
}

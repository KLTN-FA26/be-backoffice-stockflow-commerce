package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for package state and its integrity gate. */
public interface PackJpaRepository extends BaseJpaRepository<PackJpaEntity> {

    java.util.Optional<PackJpaEntity> findByPickId(java.util.UUID pickId);

    java.util.List<PackJpaEntity> findByPickIdIn(java.util.Collection<java.util.UUID> pickIds);

    /** SCRUM-287 PR T: a transfer's packing session, locked - every write of it takes this first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from PackJpaEntity k where k.id = :id")
    java.util.Optional<PackJpaEntity> lockById(@Param("id") java.util.UUID id);

    /** SCRUM-287 PR T: the transfer's packing sessions, newest first (at most one not CANCELLED). */
    java.util.List<PackJpaEntity> findByTransferOrderIdOrderByCreatedAtDesc(java.util.UUID transferOrderId);

    /** SCRUM-287 PR T: these transfers' packing sessions in a status (PACKED: ready for a load plan). */
    java.util.List<PackJpaEntity> findByTransferOrderIdInAndStatus(java.util.Collection<java.util.UUID> transferOrderIds,
            com.stockflow.fulfillment.internal.domain.PackStatus status);
}

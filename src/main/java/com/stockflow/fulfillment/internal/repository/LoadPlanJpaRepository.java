package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Load plan headers (SCRUM-287 PR D). Every write to a plan takes its row lock first. */
public interface LoadPlanJpaRepository extends BaseJpaRepository<LoadPlanJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LoadPlanJpaEntity p where p.id = :id")
    Optional<LoadPlanJpaEntity> lockById(@Param("id") UUID id);

    Optional<LoadPlanJpaEntity> findByRequestId(UUID requestId);

    List<LoadPlanJpaEntity> findByIdIn(Collection<UUID> ids);
}

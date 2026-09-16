package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link DesignDraftJpaEntity}. STARTER STUB. */
interface DesignDraftJpaRepository extends BaseJpaRepository<DesignDraftJpaEntity> {

    // Single-owner scope cannot express owner OR assigned designer. ALL/WAREHOUSE grants do
    // not bypass this explicit predicate for private customer files.
    @Query("select d from DesignDraftJpaEntity d where d.id = :id and "
            + "(d.ownerUserId = :userId or d.assignedUserId = :userId)")
    Optional<DesignDraftJpaEntity> findAccessible(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DesignDraftJpaEntity d where d.id = :id and "
            + "(d.ownerUserId = :userId or d.assignedUserId = :userId)")
    Optional<DesignDraftJpaEntity> lockAccessible(@Param("id") UUID id, @Param("userId") UUID userId);
}

package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.domain.ProjectStatus;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DesignProjectJpaEntity}; callers decide who may see a project. {@link #lockById}
 * serialises everything that counts or changes what a project holds: its designs, its library, its batches.
 */
public interface DesignProjectJpaRepository extends BaseJpaRepository<DesignProjectJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DesignProjectJpaEntity p where p.id = :id")
    Optional<DesignProjectJpaEntity> lockById(@Param("id") UUID id);

    /** A user's own projects in the given statuses, the newest first (BR-17). */
    @Query("select p from DesignProjectJpaEntity p where p.ownerUserId = :userId and p.status in :statuses "
            + "order by p.createdAt desc, p.id desc")
    Page<DesignProjectJpaEntity> findOwnedBy(@Param("userId") UUID userId,
                                             @Param("statuses") Collection<ProjectStatus> statuses, Pageable page);
}

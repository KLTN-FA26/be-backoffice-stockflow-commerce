package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.domain.ProjectStatus;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DesignProjectJpaEntity}; callers decide who may see a project. {@link #lockById}
 * serialises everything that counts or changes what a project holds: its designs, its library, its batches.
 *
 * <p>A page's design counts come in one query ({@code DesignDraftJpaRepository#countInProjects}).</p>
 */
public interface DesignProjectJpaRepository extends BaseJpaRepository<DesignProjectJpaEntity> {

    /**
     * The project, locked {@code FOR NO KEY UPDATE}: what a {@code FOR UPDATE} would add is a conflict with the
     * {@code KEY SHARE} every insert naming the project takes through its foreign key - the history row a decision
     * writes when it closes a round, under that round's design lock - so a closing project waiting on that design and
     * the decision waiting on the project would deadlock. The key never changes, so nothing needs the stronger lock.
     */
    @Query(value = "SELECT * FROM design.design_project WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<DesignProjectJpaEntity> lockById(@Param("id") UUID id);

    /** A user's own projects in the given statuses, the newest first (BR-17). */
    @Query("select p from DesignProjectJpaEntity p where p.ownerUserId = :userId and p.status in :statuses "
            + "order by p.createdAt desc, p.id desc")
    Page<DesignProjectJpaEntity> findOwnedBy(@Param("userId") UUID userId,
                                             @Param("statuses") Collection<ProjectStatus> statuses, Pageable page);
}

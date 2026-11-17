package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignProjectTextJpaEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link DesignProjectTextJpaEntity}: the texts of a design project's library. */
public interface DesignProjectTextJpaRepository extends BaseJpaRepository<DesignProjectTextJpaEntity> {

    /** The library's texts, the newest first. */
    List<DesignProjectTextJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);

    /** What the 30-text limit counts. */
    long countByProjectId(UUID projectId);

    Optional<DesignProjectTextJpaEntity> findByIdAndProjectId(UUID id, UUID projectId);
}

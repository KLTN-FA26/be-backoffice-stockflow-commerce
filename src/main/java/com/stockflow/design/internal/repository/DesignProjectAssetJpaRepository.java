package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignProjectAssetJpaEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link DesignProjectAssetJpaEntity}: a design project's library. */
public interface DesignProjectAssetJpaRepository extends BaseJpaRepository<DesignProjectAssetJpaEntity> {

    /** The library, the newest first. */
    List<DesignProjectAssetJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);

    /** What the 30-file limit counts (B43). */
    long countByProjectId(UUID projectId);

    Optional<DesignProjectAssetJpaEntity> findByIdAndProjectId(UUID id, UUID projectId);
}

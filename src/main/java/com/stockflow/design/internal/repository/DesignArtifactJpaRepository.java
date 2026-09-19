package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import java.util.List;
import java.util.UUID;

public interface DesignArtifactJpaRepository extends BaseJpaRepository<DesignArtifactJpaEntity> {
    List<DesignArtifactJpaEntity> findByDraftIdOrderByStoredAtDescIdDesc(UUID draftId);
}

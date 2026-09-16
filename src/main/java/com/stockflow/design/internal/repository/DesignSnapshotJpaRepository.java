package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import java.util.UUID;

/** Spring Data repository for {@link DesignSnapshotJpaEntity}. STARTER STUB. */
interface DesignSnapshotJpaRepository extends BaseJpaRepository<DesignSnapshotJpaEntity> {

    boolean existsByDraftId(UUID draftId);
}

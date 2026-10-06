package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import java.util.UUID;

/** Snapshot evidence. Legacy snapshots are not eligible for new workflow confirmation replay. */
public interface DesignSnapshotJpaRepository extends BaseJpaRepository<DesignSnapshotJpaEntity> {

    @org.springframework.data.jpa.repository.Query("select s from DesignSnapshotJpaEntity s where s.draftId = :draftId and s.confirmedBy is not null and s.artifactId is not null")
    java.util.Optional<DesignSnapshotJpaEntity> findByDraftId(@org.springframework.data.repository.query.Param("draftId") UUID draftId);

    boolean existsByDraftId(UUID draftId);
}

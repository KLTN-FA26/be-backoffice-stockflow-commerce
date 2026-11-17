package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;

/** Persistence access for a task's packing session. */
public interface PackJpaRepository extends BaseJpaRepository<PackJpaEntity> {

    java.util.Optional<PackJpaEntity> findByPickId(java.util.UUID pickId);

    java.util.List<PackJpaEntity> findByPickIdIn(java.util.Collection<java.util.UUID> pickIds);
}

package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Persistence access for package state and its integrity gate. */
public interface PackJpaRepository extends BaseJpaRepository<PackJpaEntity> {

    java.util.Optional<PackJpaEntity> findByPickId(java.util.UUID pickId);
}

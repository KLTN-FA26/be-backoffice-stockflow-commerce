package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.entity.StorageLocationJpaEntity;

import java.util.Optional;

/**
 * Spring Data repository for {@link StorageLocationJpaEntity}. Read side only: a location is written
 * through the bin or area that owns it, never saved here on its own - a location saved without an
 * owner is refused by the database at commit.
 */
interface StorageLocationJpaRepository extends BaseJpaRepository<StorageLocationJpaEntity> {

    /** Exact match; callers upper-case a scanned code first (every part is {@code [A-Z0-9]}). */
    Optional<StorageLocationJpaEntity> findByLocationCode(String locationCode);
}

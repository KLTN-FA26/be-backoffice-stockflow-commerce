package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PackageScanJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The append-only scans of the packing table; inserted, never changed. */
public interface PackageScanJpaRepository extends BaseJpaRepository<PackageScanJpaEntity> {

    List<PackageScanJpaEntity> findByPackageIdInOrderByScannedAtAscIdAsc(Collection<UUID> packageIds);
}

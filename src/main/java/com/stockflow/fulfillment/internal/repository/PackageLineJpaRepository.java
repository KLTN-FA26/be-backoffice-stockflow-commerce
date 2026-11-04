package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;

import java.util.Collection;
import java.util.List;

/** Running totals per parcel and order line. */
public interface PackageLineJpaRepository extends BaseJpaRepository<PackageLineJpaEntity> {

    List<PackageLineJpaEntity> findByPackageIdIn(Collection<java.util.UUID> packageIds);
}

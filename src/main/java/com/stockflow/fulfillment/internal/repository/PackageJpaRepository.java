package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Parcels: read per pack (one packing session) or per shipment. */
public interface PackageJpaRepository extends BaseJpaRepository<PackageJpaEntity> {

    List<PackageJpaEntity> findByPackIdOrderByPackageNoAsc(UUID packId);

    List<PackageJpaEntity> findByShipmentIdOrderByPackageNoAsc(UUID shipmentId);

    List<PackageJpaEntity> findByPackIdIn(Collection<UUID> packIds);
}

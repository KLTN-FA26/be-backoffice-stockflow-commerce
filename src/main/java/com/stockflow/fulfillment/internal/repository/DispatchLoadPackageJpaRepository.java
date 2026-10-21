package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Parcels on vehicles (G4-2a). */
public interface DispatchLoadPackageJpaRepository extends BaseJpaRepository<DispatchLoadPackageJpaEntity> {

    List<DispatchLoadPackageJpaEntity> findByLoadIdOrderByLoadedAtAscIdAsc(UUID loadId);

    Optional<DispatchLoadPackageJpaEntity> findByScanId(UUID scanId);

    Optional<DispatchLoadPackageJpaEntity> findByPackageIdAndActiveTrue(UUID packageId);

    List<DispatchLoadPackageJpaEntity> findByShipmentIdInAndActiveTrue(Collection<UUID> shipmentIds);
}

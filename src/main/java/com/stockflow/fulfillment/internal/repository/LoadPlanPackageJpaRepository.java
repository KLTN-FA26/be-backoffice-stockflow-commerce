package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LoadPlanPackageJpaRepository extends BaseJpaRepository<LoadPlanPackageJpaEntity> {

    List<LoadPlanPackageJpaEntity> findByLoadPlanIdOrderByAddedAtAscIdAsc(UUID loadPlanId);

    /** The plans holding these parcels now (BR-09). */
    List<LoadPlanPackageJpaEntity> findByPackageIdInAndActiveTrue(Collection<UUID> packageIds);

    List<LoadPlanPackageJpaEntity> findByShipmentIdAndActiveTrue(UUID shipmentId);

    boolean existsByPackageIdAndActiveTrue(UUID packageId);

    /** SCRUM-287 PR T: the parcels of a transfer order held by a live plan. */
    List<LoadPlanPackageJpaEntity> findByTransferOrderIdAndActiveTrue(UUID transferOrderId);
}

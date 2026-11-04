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

    /** G4-2a: the parcel a loader scanned, by the code on its label. */
    java.util.Optional<PackageJpaEntity> findByLabelCode(String labelCode);

    List<PackageJpaEntity> findByShipmentIdIn(Collection<UUID> shipmentIds);

    /** SCRUM-287 PR T: the parcels of these transfer orders (voided ones included). */
    List<PackageJpaEntity> findByTransferOrderIdIn(Collection<UUID> transferOrderIds);

    /** LP2-8: a parcel not voided that ships this stock carton as it is. */
    java.util.Optional<PackageJpaEntity> findFirstByStockPackageIdAndStatusNot(UUID stockPackageId,
            com.stockflow.fulfillment.internal.domain.PackageStatus status);
}

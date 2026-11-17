package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.ShipmentPodFileJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Proofs of delivery kept with shipments (3-6). */
public interface ShipmentPodFileJpaRepository extends BaseJpaRepository<ShipmentPodFileJpaEntity> {

    List<ShipmentPodFileJpaEntity> findByShipmentIdOrderByUploadedAtAscIdAsc(UUID shipmentId);

    Optional<ShipmentPodFileJpaEntity> findByIdAndShipmentId(UUID id, UUID shipmentId);

    Optional<ShipmentPodFileJpaEntity> findByStorageKeyAndShipmentId(String storageKey, UUID shipmentId);

    /** Those of {@code keys} a shipment keeps (the orphaned-file sweep). */
    @Query("select f.storageKey from ShipmentPodFileJpaEntity f where f.storageKey in :keys")
    List<String> storageKeysIn(@Param("keys") Collection<String> keys);
}

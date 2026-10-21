package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shipments: always read for one pack (a task's packing) or one order. */
public interface ShipmentJpaRepository extends BaseJpaRepository<ShipmentJpaEntity> {

    List<ShipmentJpaEntity> findByPackIdOrderByReadyAtAscIdAsc(UUID packId);

    List<ShipmentJpaEntity> findByOrderIdOrderByReadyAtAscIdAsc(UUID orderId);

    Optional<ShipmentJpaEntity> findByRequestId(UUID requestId);

    long countByOrderId(UUID orderId);

    List<ShipmentJpaEntity> findByPackIdIn(java.util.Collection<UUID> packIds);
}

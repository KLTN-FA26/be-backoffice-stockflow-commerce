package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Shipments on manifests (G4-2a). */
public interface DispatchLoadShipmentJpaRepository extends BaseJpaRepository<DispatchLoadShipmentJpaEntity> {

    List<DispatchLoadShipmentJpaEntity> findByLoadIdOrderByAddedAtAscIdAsc(UUID loadId);

    /** The open-or-dispatched manifests the shipments are on now (at most one each). */
    List<DispatchLoadShipmentJpaEntity> findByShipmentIdInAndActiveTrue(Collection<UUID> shipmentIds);

    List<DispatchLoadShipmentJpaEntity> findByLoadIdInAndActiveTrue(Collection<UUID> loadIds);

    /** SCRUM-287 PR T: the manifests a transfer order is on now (the vehicles of its plan). */
    List<DispatchLoadShipmentJpaEntity> findByTransferOrderIdAndActiveTrue(UUID transferOrderId);
}

package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shipments: always read for one pack (a task's packing) or one order. */
public interface ShipmentJpaRepository extends BaseJpaRepository<ShipmentJpaEntity> {

    /** A shipment's id and status, read without loading it (3-5: its lock reads it fresh). */
    interface ShipmentState {
        UUID getId();

        String getCode();

        UUID getOrderId();

        com.stockflow.fulfillment.internal.domain.ShipmentStatus getStatus();
    }

    /**
     * The shipments that left on the load, by order and then id: applying an outcome to each locks the shipment and
     * then its order, so two loads carrying shipments of the same orders take the order locks in the same order.
     */
    @org.springframework.data.jpa.repository.Query("select s.id as id, s.code as code, s.orderId as orderId, "
            + "s.status as status from ShipmentJpaEntity s where s.loadId = :loadId order by s.orderId, s.id")
    List<ShipmentState> statesOnLoad(@org.springframework.data.repository.query.Param("loadId") UUID loadId);

    /** A shipment on a load, read without loading it. */
    interface ShipmentOnLoad {
        UUID getId();

        UUID getLoadId();

        String getCode();

        com.stockflow.fulfillment.internal.domain.ShipmentStatus getStatus();
    }

    /** The shipments that left on these loads, for checking a file of outcomes line by line in one query. */
    @org.springframework.data.jpa.repository.Query("select s.id as id, s.loadId as loadId, s.code as code, "
            + "s.status as status from ShipmentJpaEntity s where s.loadId in :loadIds order by s.loadId, s.id")
    List<ShipmentOnLoad> statesOnLoads(
            @org.springframework.data.repository.query.Param("loadIds") java.util.Collection<UUID> loadIds);

    /** A shipment's code, read without loading it. */
    @org.springframework.data.jpa.repository.Query("select s.code from ShipmentJpaEntity s where s.id = :id")
    Optional<String> codeOf(@org.springframework.data.repository.query.Param("id") UUID id);

    List<ShipmentJpaEntity> findByPackIdOrderByReadyAtAscIdAsc(UUID packId);

    List<ShipmentJpaEntity> findByOrderIdOrderByReadyAtAscIdAsc(UUID orderId);

    Optional<ShipmentJpaEntity> findByRequestId(UUID requestId);

    long countByOrderId(UUID orderId);

    List<ShipmentJpaEntity> findByPackIdIn(java.util.Collection<UUID> packIds);

    List<ShipmentJpaEntity> findByIdIn(java.util.Collection<UUID> ids);
}

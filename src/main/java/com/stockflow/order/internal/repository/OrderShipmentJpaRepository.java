package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderShipmentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The shipments of orders, as the order module records them (G3-2). Package-private: the aggregate owns them. */
interface OrderShipmentJpaRepository extends JpaRepository<OrderShipmentJpaEntity, UUID> {

    List<OrderShipmentJpaEntity> findByOrderIdOrderByHandedOverAtAscIdAsc(UUID orderId);

    List<OrderShipmentJpaEntity> findByOrderIdIn(Collection<UUID> orderIds);

    /** The ids of these orders that have a shipment (WF4-PF-STATUS: a list row tells a partial order by it). */
    @Query("select distinct s.orderId from OrderShipmentJpaEntity s where s.orderId in :orderIds")
    List<UUID> findOrderIdsWithShipments(@Param("orderIds") Collection<UUID> orderIds);
}

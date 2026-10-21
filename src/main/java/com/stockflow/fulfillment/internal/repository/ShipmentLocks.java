package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Shipments under their row lock, always taken in id order, and read again once locked (G4-2a).
 *
 * <p>A native {@code SELECT … ORDER BY id FOR UPDATE} rather than a locking JPQL query: Hibernate may lock a JPQL
 * result row by row after reading it ("follow-on locking"), which hands back what it read before waiting - a
 * dispatcher who waited for another's signature would then compute the COD from the shipments as they were before it.
 * Each row is refreshed after the lock for the same reason (the persistence context may hold an older copy).</p>
 */
@Component
public class ShipmentLocks {

    private final EntityManager entityManager;

    ShipmentLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** These shipments, locked in id order. */
    public List<ShipmentJpaEntity> lock(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        return refreshed(ids("SELECT id FROM fulfillment.shipment WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                shipmentIds));
    }

    /**
     * Every shipment of these orders, locked in id order: a handover reads the order's other shipments (which wait,
     * which left) to fix a COD, so two signatures touching one order must not read them side by side.
     */
    public List<ShipmentJpaEntity> lockOfOrders(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return refreshed(ids("SELECT id FROM fulfillment.shipment WHERE order_id IN (:ids) ORDER BY id FOR UPDATE",
                orderIds));
    }

    /** The orders of these shipments, read without loading them (an order id never changes). */
    @SuppressWarnings("unchecked")
    public List<UUID> ordersOf(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        return ((List<Object>) entityManager.createNativeQuery(
                        "SELECT DISTINCT order_id FROM fulfillment.shipment WHERE id IN (:ids)")
                .setParameter("ids", shipmentIds).getResultList()).stream().map(o -> (UUID) o).toList();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> ids(String sql, Collection<UUID> keys) {
        return ((List<Object>) entityManager.createNativeQuery(sql).setParameter("ids", keys).getResultList())
                .stream().map(o -> (UUID) o).toList();
    }

    private List<ShipmentJpaEntity> refreshed(List<UUID> ids) {
        return ids.stream().map(id -> {
            ShipmentJpaEntity row = entityManager.find(ShipmentJpaEntity.class, id);
            entityManager.refresh(row);
            return row;
        }).toList();
    }
}

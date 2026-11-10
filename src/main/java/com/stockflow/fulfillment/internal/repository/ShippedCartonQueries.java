package com.stockflow.fulfillment.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which cartons left on which shipment (team plan 4D-2), read straight from the pick and the packing records.
 *
 * <p>The shipping carton is the LPN carton itself (team plan 4C-6, kltn-docs 08 BR-02): a shipment carried the
 * cartons its {@code fulfillment.package} rows name, LABELED on it, each the carton its pick line took whole.</p>
 */
@Component
public class ShippedCartonQueries {

    /** One carton as the pick recorded it. */
    public record Row(String lpn, String sku, String lotNumber, int quantity) {
    }

    /** A shipment's own facts. */
    public record Shipment(UUID id, String code, UUID orderId, UUID warehouseId, String status) {
    }

    private final EntityManager entityManager;

    ShippedCartonQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Shipment> shipment(UUID shipmentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, code, order_id, warehouse_id, CAST(status AS text)
                          FROM fulfillment.shipment WHERE id = :id""")
                .setParameter("id", shipmentId).getResultList();
        return rows.stream().findFirst().map(r -> new Shipment((UUID) r[0], (String) r[1], (UUID) r[2], (UUID) r[3],
                (String) r[4]));
    }

    /** The order's DELIVERED shipments. */
    @SuppressWarnings("unchecked")
    public List<UUID> deliveredShipmentsOf(UUID orderId) {
        return ((List<Object>) entityManager.createNativeQuery("""
                        SELECT id FROM fulfillment.shipment WHERE order_id = :order AND status = 'DELIVERED'
                         ORDER BY id""")
                .setParameter("order", orderId).getResultList()).stream().map(id -> (UUID) id).toList();
    }

    /**
     * The cartons of the order {@code orderId} that left on these shipments, by LPN: the shipping cartons labelled on
     * them, each with what its pick line took.
     */
    @SuppressWarnings("unchecked")
    public List<Row> cartonsOn(UUID orderId, Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT l.lpn, l.sku, l.lot_number, l.picked_qty
                          FROM fulfillment.package p
                          JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                          JOIN fulfillment.pick k ON k.id = l.pick_id
                         WHERE k.order_id = :order AND p.shipment_id IN (:shipments) AND p.status = 'LABELED'
                           AND l.picked_qty > 0
                         ORDER BY l.lpn, l.id""")
                .setParameter("order", orderId).setParameter("shipments", shipmentIds).getResultList();
        return rows.stream()
                .map(r -> new Row((String) r[0], (String) r[1], (String) r[2], ((Number) r[3]).intValue()))
                .distinct()
                .toList();
    }
}

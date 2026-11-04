package com.stockflow.procurement.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Which purchase order lines are still open for some inventory items (SCRUM-461, BR-PRD-005): what a
 * person is told when a product is discontinued or deleted. Native SQL on this module's own tables.
 *
 * <p>"Open" is a line still to be received ({@code OPEN}, {@code PARTIALLY_RECEIVED}) on a purchase order
 * that is not yet over: it is still being drafted, waiting for approval, approved, confirmed with the
 * supplier, or partly received. Subcontract orders count: the goods of a running production order are on
 * their way like any other.</p>
 */
@Repository
public class PoLineReferences {

    /** One open line: its purchase order and the inventory item it orders. */
    public record Row(UUID purchaseOrderId, String poNumber, UUID inventoryItemId) {
    }

    private final EntityManager entityManager;

    PoLineReferences(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** At most {@code limit} lines, newest purchase order first. */
    public List<Row> openLines(Collection<UUID> inventoryItemIds, int limit) {
        if (inventoryItemIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT po.id, po.po_number, l.inventory_item_id
                          FROM procurement.purchase_order_lines l
                          JOIN procurement.purchase_orders po ON po.id = l.po_id
                         WHERE l.inventory_item_id IN (:items)
                           AND l.status IN ('OPEN', 'PARTIALLY_RECEIVED')
                           AND po.status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED')
                         ORDER BY po.created_at DESC, po.id, l.line_no""")
                .setParameter("items", inventoryItemIds)
                .setMaxResults(limit)
                .getResultList();
        return rows.stream()
                .map(r -> new Row((UUID) r[0], (String) r[1], (UUID) r[2]))
                .toList();
    }
}

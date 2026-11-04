package com.stockflow.order.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Which order lines are placed and not yet shipped, for some SKUs (SCRUM-461, BR-PRD-005): what a person is
 * told when a product is discontinued or deleted. Native SQL on this module's own tables.
 *
 * <p>An order counts from the moment it is placed ({@code PENDING_PAYMENT}) until it ships; a cart
 * ({@code DRAFT}) is not an order, and an order that has shipped or ended has nothing left to fulfil.</p>
 */
@Repository
public class OrderLineReferences {

    /** One unshipped line: its order and the SKU. */
    public record Row(UUID orderId, String orderNumber, String sku) {
    }

    private final EntityManager entityManager;

    OrderLineReferences(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** At most {@code limit} lines, most recently placed order first. */
    public List<Row> unshippedLines(Collection<String> skus, int limit) {
        if (skus.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT o.id, o.order_number, l.sku
                          FROM ordering.order_line l
                          JOIN ordering.customer_order o ON o.id = l.order_id
                         WHERE l.sku IN (:skus)
                           AND o.status IN (:unshipped)
                         ORDER BY o.placed_at DESC, o.id, l.sku""")
                .setParameter("skus", skus)
                // From the state machine (G3-2): every status that still holds stock for the order, so a status
                // added later is not forgotten here.
                .setParameter("unshipped", java.util.Arrays.stream(com.stockflow.order.api.OrderStatus.values())
                        .filter(com.stockflow.order.api.OrderStatus::holdsStock).map(Enum::name).toList())
                .setMaxResults(limit)
                .getResultList();
        return rows.stream()
                .map(r -> new Row((UUID) r[0], (String) r[1], (String) r[2]))
                .toList();
    }
}

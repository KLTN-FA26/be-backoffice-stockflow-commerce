package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ProductionOrderStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Which production order lines still have goods to come, for some SKUs (kltn-docs 01 BR-14): what a person is told
 * when a product is discontinued or deleted. Native SQL on this module's own tables.
 *
 * <p>A line counts while its production order is open - drawn up, sent, being made or partly received - and it has
 * not received every carton it orders: those cartons are still on their way to the warehouse, and are received
 * whatever happened to the product since. A production order received, cancelled or closed brings nothing more.</p>
 */
@Repository
public class ProductionOrderLineReferences {

    /** One line with goods still to come: its production order and the SKU. */
    public record Row(UUID productionOrderId, String code, String sku) {
    }

    private final EntityManager entityManager;

    ProductionOrderLineReferences(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** At most {@code limit} lines, the most recently drawn up production order first. */
    public List<Row> openLines(Collection<String> skus, int limit) {
        if (skus.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT p.id, p.code, l.sku
                          FROM ordering.production_order_line l
                          JOIN ordering.production_order p ON p.id = l.production_order_id
                         WHERE l.sku IN (:skus)
                           AND p.status IN (:open)
                           AND l.received_cartons < l.cartons
                         ORDER BY p.created_at DESC, p.id, l.sku""")
                .setParameter("skus", skus)
                // From the state machine: a status added later is counted by what it says, not forgotten here.
                .setParameter("open", Arrays.stream(ProductionOrderStatus.values())
                        .filter(ProductionOrderStatus::open).map(Enum::name).toList())
                .setMaxResults(limit)
                .getResultList();
        return rows.stream()
                .map(r -> new Row((UUID) r[0], (String) r[1], (String) r[2]))
                .toList();
    }
}

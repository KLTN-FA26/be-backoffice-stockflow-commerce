package com.stockflow.inventory.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock on its way to becoming available, per SKU: two aggregate queries, one over stock rows and one over transfer
 * lines, whatever the number of SKUs.
 *
 * <p>A transfer's remainder is in transit while the transfer is IN_TRANSIT or PARTIALLY_RECEIVED; once it is RECEIVED,
 * COMPLETED or CLOSED, whatever was not counted in was settled by the receipt, not left on the road.</p>
 */
@Repository
public class IncomingStockRows {

    private final EntityManager entityManager;

    IncomingStockRows(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public record Incoming(int inbound, int quarantine, int inTransit) {
    }

    /** By SKU; a SKU with nothing incoming is absent. An empty input costs no query. */
    public Map<String, Incoming> bySku(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<String, Incoming> found = new HashMap<>();
        rows("""
                SELECT sku,
                       COALESCE(SUM(on_hand) FILTER (WHERE status = 'INBOUND'), 0),
                       COALESCE(SUM(on_hand) FILTER (WHERE status = 'QUARANTINE'), 0)
                  FROM inventory.stock_item
                 WHERE sku IN (:skus) AND status IN ('INBOUND', 'QUARANTINE')
                 GROUP BY sku""", skus)
                .forEach(row -> found.put((String) row[0],
                        new Incoming(((Number) row[1]).intValue(), ((Number) row[2]).intValue(), 0)));
        rows("""
                SELECT l.sku, COALESCE(SUM(GREATEST(l.shipped_qty - l.received_qty - l.damaged_qty, 0)), 0)
                  FROM inventory.transfer_order_line l
                  JOIN inventory.transfer_order o ON o.id = l.transfer_order_id
                 WHERE l.sku IN (:skus) AND o.status IN ('IN_TRANSIT', 'PARTIALLY_RECEIVED')
                 GROUP BY l.sku""", skus)
                .forEach(row -> found.merge((String) row[0],
                        new Incoming(0, 0, ((Number) row[1]).intValue()),
                        (stock, transit) -> new Incoming(stock.inbound(), stock.quarantine(), transit.inTransit())));
        return found;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Collection<String> skus) {
        return entityManager.createNativeQuery(sql).setParameter("skus", skus).getResultList();
    }
}

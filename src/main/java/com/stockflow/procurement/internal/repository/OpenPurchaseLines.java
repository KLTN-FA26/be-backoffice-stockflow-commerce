package com.stockflow.procurement.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Open purchasing per inventory item, in one aggregate query, for {@code OnOrderQueries}.
 *
 * <p>"Received" is what the purchase order screen counts: confirmed receipts that were not cancelled
 * ({@link PurchaseOrderRepositoryAdapter}'s {@code receivedByLine}). A line that is RECEIVED, CLOSED (short-closed) or
 * CANCELLED has nothing left to arrive whatever its numbers say, so only OPEN and PARTIALLY_RECEIVED lines count.</p>
 */
@Repository
public class OpenPurchaseLines {

    private final EntityManager entityManager;

    OpenPurchaseLines(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** @param onOrder base units; {@code nextExpectedDate} null when no open order has a date */
    public record OpenPurchasing(int onOrder, int awaitingApproval, LocalDate nextExpectedDate) {
    }

    /** By inventory item id; an item with nothing open is absent. An empty input costs no query. */
    public Map<UUID, OpenPurchasing> byItem(Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT l.inventory_item_id,
                               COALESCE(SUM(GREATEST(l.ordered_qty - COALESCE(r.received, 0), 0))
                                        FILTER (WHERE o.status IN ('APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED')
                                                  AND l.status IN ('OPEN', 'PARTIALLY_RECEIVED')), 0),
                               COALESCE(SUM(l.ordered_qty)
                                        FILTER (WHERE o.status IN ('DRAFT', 'PENDING_APPROVAL')), 0),
                               MIN(o.expected_date)
                                        FILTER (WHERE o.status IN ('APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED')
                                                  AND l.status IN ('OPEN', 'PARTIALLY_RECEIVED')
                                                  AND l.ordered_qty > COALESCE(r.received, 0))
                          FROM procurement.purchase_order_lines l
                          JOIN procurement.purchase_orders o ON o.id = l.po_id
                          LEFT JOIN LATERAL (
                                SELECT SUM(gl.received_qty) AS received
                                  FROM procurement.goods_receipt_lines gl
                                  JOIN procurement.goods_receipts g ON g.id = gl.receipt_id
                                 WHERE gl.po_line_id = l.id AND g.confirmed_at IS NOT NULL AND g.status <> 'CANCELLED'
                               ) r ON TRUE
                         WHERE l.inventory_item_id IN (:items)
                           AND o.po_type = 'STANDARD'
                           AND o.status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED')
                         GROUP BY l.inventory_item_id""")
                .setParameter("items", itemIds)
                .getResultList();
        Map<UUID, OpenPurchasing> open = new HashMap<>();
        for (Object[] row : rows) {
            open.put((UUID) row[0], new OpenPurchasing(((Number) row[1]).intValue(), ((Number) row[2]).intValue(),
                    date(row[3])));
        }
        return open;
    }

    /** The driver's DATE type depends on the Hibernate version; the value does not. */
    private static LocalDate date(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate d -> d;
            case Date d -> d.toLocalDate();
            default -> LocalDate.parse(value.toString());
        };
    }
}

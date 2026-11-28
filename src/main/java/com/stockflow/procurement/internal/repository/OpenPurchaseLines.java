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
 * CANCELLED has nothing left to arrive whatever its numbers say, so only OPEN and PARTIALLY_RECEIVED lines count. A
 * WITHDRAWN line was taken off a draft and is kept only for the revision that froze it, so a draft counts the rest.</p>
 *
 * <p>An order the supplier refused ({@code supplier_confirmation_status = 'REJECTED'}) stays CONFIRMED, but receiving
 * refuses it ({@code ReceivingPurchaseOrder}), so its open lines leave {@code onOrder} for {@code rejectedBySupplier}.
 * The refusal is only possible before any receipt, so what is open on it is the whole order.</p>
 */
@Repository
public class OpenPurchaseLines {

    private final EntityManager entityManager;

    OpenPurchaseLines(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * @param onOrder            base units; {@code nextExpectedDate} null when no open order has a date
     * @param overdue            the part of {@code onOrder} on orders sent to the supplier and expected before the
     *                           given day; an APPROVED order is not sent yet, so it is never overdue
     * @param rejectedBySupplier open units on orders the supplier refused, not part of {@code onOrder}
     */
    public record OpenPurchasing(int onOrder, int awaitingApproval, LocalDate nextExpectedDate, int overdue,
                                 int rejectedBySupplier) {
    }

    /**
     * By inventory item id; an item with nothing open is absent. An empty input costs no query.
     *
     * @param today the business day an expected date is overdue against: one before it is late
     */
    public Map<UUID, OpenPurchasing> byItem(Collection<UUID> itemIds, LocalDate today) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT p.inventory_item_id,
                               COALESCE(SUM(p.outstanding) FILTER (WHERE p.awaited AND NOT p.refused), 0),
                               COALESCE(SUM(p.ordered_qty) FILTER (WHERE p.drafted), 0),
                               MIN(p.expected_date) FILTER (WHERE p.awaited AND NOT p.refused AND p.outstanding > 0),
                               COALESCE(SUM(p.outstanding)
                                        FILTER (WHERE p.awaited AND p.sent AND NOT p.refused
                                                AND p.expected_date < :today), 0),
                               COALESCE(SUM(p.outstanding) FILTER (WHERE p.awaited AND p.refused), 0)
                          FROM (SELECT l.inventory_item_id, l.ordered_qty, o.expected_date,
                                       GREATEST(l.ordered_qty - COALESCE(r.received, 0), 0) AS outstanding,
                                       o.status IN ('APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED')
                                           AND l.status IN ('OPEN', 'PARTIALLY_RECEIVED') AS awaited,
                                       o.supplier_confirmation_status = 'REJECTED' AS refused,
                                       o.status IN ('CONFIRMED', 'PARTIALLY_RECEIVED') AS sent,
                                       o.status IN ('DRAFT', 'PENDING_APPROVAL') AND l.status <> 'WITHDRAWN' AS drafted
                                  FROM procurement.purchase_order_lines l
                                  JOIN procurement.purchase_orders o ON o.id = l.po_id
                                  LEFT JOIN LATERAL (
                                        SELECT SUM(gl.received_qty) AS received
                                          FROM procurement.goods_receipt_lines gl
                                          JOIN procurement.goods_receipts g ON g.id = gl.receipt_id
                                         WHERE gl.po_line_id = l.id AND g.confirmed_at IS NOT NULL
                                           AND g.status <> 'CANCELLED'
                                       ) r ON TRUE
                                 WHERE l.inventory_item_id IN (:items)
                                   AND o.po_type = 'STANDARD'
                                   AND o.status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED',
                                                    'PARTIALLY_RECEIVED')) p
                         GROUP BY p.inventory_item_id""")
                .setParameter("items", itemIds)
                .setParameter("today", today)
                .getResultList();
        Map<UUID, OpenPurchasing> open = new HashMap<>();
        for (Object[] row : rows) {
            open.put((UUID) row[0], new OpenPurchasing(((Number) row[1]).intValue(), ((Number) row[2]).intValue(),
                    date(row[3]), ((Number) row[4]).intValue(), ((Number) row[5]).intValue()));
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

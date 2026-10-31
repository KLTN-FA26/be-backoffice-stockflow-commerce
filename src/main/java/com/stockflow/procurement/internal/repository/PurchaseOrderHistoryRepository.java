package com.stockflow.procurement.internal.repository;

import com.stockflow.common.api.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads of an order's revisions, approvals and timeline for the history endpoints (SCRUM-117, design section 9).
 * Native SQL projections on purpose: every row here is append-only history that is never loaded as an entity, and each
 * method is one statement however many rows it returns - the timeline is paged by the database, the revision list
 * carries each revision's last decision in the same statement, and the pending amendments of a page of orders are
 * fetched together.
 *
 * <p>Names are not joined here: {@code identity} owns its schema, so rows carry user ids and the service resolves
 * them once per response through {@code identity :: api}.</p>
 */
@Repository
public class PurchaseOrderHistoryRepository {

    /** The pointers of the order header the history is read against. */
    public record OrderHeader(UUID warehouseId, UUID activeRevisionId, UUID pendingRevisionId) {
    }

    /**
     * One revision with its last decision. {@code submission} is true when a {@code SUBMITTED} event names the
     * revision; {@code approved} when any approval row of it is {@code APPROVED} or {@code AUTO_APPROVED}; the
     * {@code decision*} fields are those of its last approval row, null when nobody decided it. The three
     * amounts are read as text with {@code ->>}, which keeps the scale stored (11000000.00): going through a JSON
     * tree strips trailing zeros and turns it into 1.1E+7.
     */
    public record RevisionRow(UUID id, long revisionNo, UUID changedBy, Instant changedAt, String reason,
                              String snapshotHeader, boolean submission, boolean approved, String decision,
                              Instant decidedAt, UUID decidedBy, String decisionReason, BigDecimal limitAmount,
                              BigDecimal totalAmount, BigDecimal subtotal, BigDecimal taxTotal) {
    }

    /** A revision's line as it froze it; the SKU is the inventory item's, resolved by the caller. */
    public record LineRow(UUID lineId, int lineNo, UUID inventoryItemId, String unit, BigDecimal quantity,
                          BigDecimal unitPrice, BigDecimal taxRate, BigDecimal lineSubtotal, BigDecimal lineTax,
                          BigDecimal lineTotal, String note) {
    }

    public record EventRow(UUID id, String action, String fromStatus, String toStatus, UUID actorId, String reason,
                           Long revisionNo, String payload, Instant occurredAt) {
    }

    public record PendingRow(UUID purchaseOrderId, UUID revisionId, long revisionNo, UUID requestedBy, Instant requestedAt,
                             String reason, BigDecimal totalAmount) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<OrderHeader> header(UUID purchaseOrderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT warehouse_id, active_revision_id, pending_revision_id
                          FROM procurement.purchase_orders WHERE id = :po""")
                .setParameter("po", purchaseOrderId)
                .getResultList();
        return rows.stream().findFirst()
                .map(r -> new OrderHeader((UUID) r[0], (UUID) r[1], (UUID) r[2]));
    }

    /** Newest first. The last decision is the approval row with the highest step. */
    public List<RevisionRow> revisions(UUID purchaseOrderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT r.id, r.revision_no, r.changed_by, r.changed_at, r.change_summary,
                               CAST(r.snapshot_header AS text),
                               EXISTS (SELECT 1 FROM procurement.purchase_order_events e
                                        WHERE e.po_revision_id = r.id AND e.action = 'SUBMITTED'),
                               EXISTS (SELECT 1 FROM procurement.purchase_order_approvals x
                                        WHERE x.po_revision_id = r.id AND x.decision IN ('APPROVED', 'AUTO_APPROVED')),
                               a.decision, a.decision_at, a.approver_id, a.reason, a.limit_amount,
                               r.snapshot_header ->> 'totalAmount', r.snapshot_header ->> 'subtotal',
                               r.snapshot_header ->> 'taxTotal'
                          FROM procurement.purchase_order_revisions r
                          LEFT JOIN LATERAL (SELECT z.decision, z.decision_at, z.approver_id, z.reason, z.limit_amount
                                               FROM procurement.purchase_order_approvals z
                                              WHERE z.po_revision_id = r.id
                                              ORDER BY z.step_no DESC LIMIT 1) a ON TRUE
                         WHERE r.po_id = :po
                         ORDER BY r.revision_no DESC""")
                .setParameter("po", purchaseOrderId)
                .getResultList();
        return rows.stream().map(r -> new RevisionRow((UUID) r[0], ((Number) r[1]).longValue(), (UUID) r[2],
                NativeValues.instant(r[3]), (String) r[4], (String) r[5], (Boolean) r[6], (Boolean) r[7],
                (String) r[8], NativeValues.instant(r[9]), (UUID) r[10], (String) r[11], (BigDecimal) r[12],
                decimal(r[13]), decimal(r[14]), decimal(r[15]))).toList();
    }

    private static BigDecimal decimal(Object text) {
        return text == null ? null : new BigDecimal((String) text);
    }

    /** The lines of one revision, by line number. */
    public List<LineRow> lines(UUID revisionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT lr.po_line_id, l.line_no, lr.inventory_item_id, lr.uom, lr.ordered_qty, lr.unit_price,
                               lr.tax_rate, lr.line_subtotal, lr.line_tax, lr.line_total, lr.note
                          FROM procurement.purchase_order_line_revisions lr
                          JOIN procurement.purchase_order_lines l ON l.id = lr.po_line_id
                         WHERE lr.po_revision_id = :revision
                         ORDER BY l.line_no""")
                .setParameter("revision", revisionId)
                .getResultList();
        return rows.stream().map(r -> new LineRow((UUID) r[0], ((Number) r[1]).intValue(), (UUID) r[2], (String) r[3],
                (BigDecimal) r[4], (BigDecimal) r[5], (BigDecimal) r[6], (BigDecimal) r[7], (BigDecimal) r[8],
                (BigDecimal) r[9], (String) r[10])).toList();
    }

    /** Newest first; the id breaks ties between events of one transaction, which share a timestamp. */
    public PageResponse<EventRow> events(UUID purchaseOrderId, int page, int size) {
        long total = ((Number) entityManager.createNativeQuery("""
                        SELECT count(*) FROM procurement.purchase_order_events WHERE po_id = :po""")
                .setParameter("po", purchaseOrderId)
                .getSingleResult()).longValue();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT e.id, e.action, e.from_status, e.to_status, e.actor_id, e.reason, r.revision_no,
                               CAST(e.payload AS text), e.created_at
                          FROM procurement.purchase_order_events e
                          LEFT JOIN procurement.purchase_order_revisions r ON r.id = e.po_revision_id
                         WHERE e.po_id = :po
                         ORDER BY e.created_at DESC, e.id DESC
                         LIMIT :size OFFSET :offset""")
                .setParameter("po", purchaseOrderId)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();
        List<EventRow> items = rows.stream().map(r -> new EventRow((UUID) r[0], (String) r[1], (String) r[2],
                (String) r[3], (UUID) r[4], (String) r[5], r[6] == null ? null : ((Number) r[6]).longValue(),
                (String) r[7], NativeValues.instant(r[8]))).toList();
        return PageResponse.of(items, page, size, total);
    }

    /**
     * The amendment waiting on each of these orders, in one statement. The one definition of
     * "pending": {@code PurchaseOrderRepository.pendingAmendment} delegates here. An order in PENDING_APPROVAL points at its first submission,
     * which is not an amendment. The total is the revision's lines after tax.
     */
    public Map<UUID, PendingRow> pendingAmendments(Collection<UUID> purchaseOrderIds) {
        if (purchaseOrderIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT p.id, r.id, r.revision_no, r.changed_by, r.changed_at, r.change_summary,
                               (SELECT COALESCE(SUM(lr.line_total), 0) FROM procurement.purchase_order_line_revisions lr
                                 WHERE lr.po_revision_id = r.id)
                          FROM procurement.purchase_orders p
                          JOIN procurement.purchase_order_revisions r ON r.id = p.pending_revision_id
                         WHERE p.id IN (:ids) AND r.kind = 'AMENDMENT' AND p.status <> 'PENDING_APPROVAL'""")
                .setParameter("ids", purchaseOrderIds)
                .getResultList();
        Map<UUID, PendingRow> pending = new HashMap<>();
        for (Object[] r : rows) {
            pending.put((UUID) r[0], new PendingRow((UUID) r[0], (UUID) r[1], ((Number) r[2]).longValue(),
                    (UUID) r[3], NativeValues.instant(r[4]), (String) r[5], (BigDecimal) r[6]));
        }
        return pending;
    }
}

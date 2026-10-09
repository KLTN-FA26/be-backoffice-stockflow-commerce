package com.stockflow.procurement.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrders;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Native SQL over {@code procurement.purchase_orders} / {@code purchase_order_lines}, this module's own
 * schema. Interim by design: the purchase-order code moves onto these tables in phase P4 of the C4 plan,
 * and its repository then replaces this adapter. Until then no JPA entity maps them, so that P4 is free
 * to choose its own mapping.
 */
@Repository
class ReceivingPurchaseOrderAdapter implements ReceivingPurchaseOrders {

    private final EntityManager entityManager;

    ReceivingPurchaseOrderAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ReceivingPurchaseOrder> find(UUID purchaseOrderId) {
        return load(purchaseOrderId, false);
    }

    @Override
    public Optional<ReceivingPurchaseOrder> lock(UUID purchaseOrderId) {
        return load(purchaseOrderId, true);
    }

    private Optional<ReceivingPurchaseOrder> load(UUID id, boolean forUpdate) {
        // FOR UPDATE OF p: the supplier row is read, not locked.
        @SuppressWarnings("unchecked")
        List<Object[]> header = entityManager.createNativeQuery("""
                        SELECT p.po_number, p.status, p.supplier_id, p.warehouse_id, p.active_revision_id,
                               s.over_receipt_tolerance, p.supplier_confirmation_status
                          FROM procurement.purchase_orders p
                          JOIN procurement.suppliers s ON s.id = p.supplier_id
                         WHERE p.id = :id""" + (forUpdate ? " FOR UPDATE OF p" : ""))
                .setParameter("id", id)
                .getResultList();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Object[] h = header.get(0);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, line_no, inventory_item_id, ordered_qty, status
                          FROM procurement.purchase_order_lines WHERE po_id = :id ORDER BY line_no""")
                .setParameter("id", id)
                .getResultList();
        List<ReceivingPurchaseOrder.Line> lines = rows.stream().map(r -> new ReceivingPurchaseOrder.Line(
                (UUID) r[0], ((Number) r[1]).intValue(), (UUID) r[2], (BigDecimal) r[3],
                ReceivingPurchaseOrder.LineStatus.valueOf((String) r[4]))).toList();
        return Optional.of(new ReceivingPurchaseOrder(id, (String) h[0],
                ReceivingPurchaseOrder.Status.valueOf((String) h[1]), (UUID) h[2], (UUID) h[3], (UUID) h[4],
                (BigDecimal) h[5], "REJECTED".equals(h[6]), lines));
    }

    @Override
    public void recordProgress(ReceivingPurchaseOrder order, Map<UUID, ReceivingPurchaseOrder.LineStatus> lineStatuses,
                               ReceivingPurchaseOrder.Status newStatus, UUID actorId, UUID receiptId,
                               String receiptNumber) {
        lineStatuses.forEach((lineId, status) -> entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_order_lines
                           SET status = :status, version = version + 1, last_modified_at = NOW(),
                               last_modified_by = 'goods-receipt'
                         WHERE id = :id AND status <> :status""")
                .setParameter("status", status.name())
                .setParameter("id", lineId)
                .executeUpdate());
        if (newStatus == order.status()) {
            return;
        }
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_orders
                           SET status = :status, version = version + 1, last_modified_at = NOW(),
                               last_modified_by = 'goods-receipt'
                         WHERE id = :id""")
                .setParameter("status", newStatus.name())
                .setParameter("id", order.id())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_events
                               (id, po_id, po_revision_id, action, actor_id, from_status, to_status, reason, payload,
                                created_at, created_by)
                        VALUES (:id, :po, :revision, :action, :actor, :from, :to, :reason,
                                jsonb_build_object('receiptId', CAST(:receiptId AS text), 'receiptNumber', CAST(:receiptNumber AS text)),
                                NOW(), 'goods-receipt')""")
                .setParameter("id", Identifiers.newId())
                .setParameter("po", order.id())
                .setParameter("revision", order.activeRevisionId())
                .setParameter("action", newStatus.name())
                .setParameter("actor", actorId)
                .setParameter("from", order.status().name())
                .setParameter("to", newStatus.name())
                .setParameter("reason", "Goods receipt " + receiptNumber)
                .setParameter("receiptId", receiptId.toString())
                .setParameter("receiptNumber", receiptNumber)
                .executeUpdate();
    }
}

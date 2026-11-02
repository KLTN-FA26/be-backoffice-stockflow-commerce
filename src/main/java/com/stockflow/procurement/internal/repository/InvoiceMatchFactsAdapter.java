package com.stockflow.procurement.internal.repository;

import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.internal.domain.InvoiceMatchFacts;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link InvoiceMatchFacts} in SQL over procurement's own tables. SKUs come from inventory through its API, as the
 * purchase order adapter takes them: no join across schemas.
 *
 * <p>A receipt counts once it is posted - not a draft, not waiting for approval, not cancelled - the way receiving
 * counts it against the order (BR-RCP-001). A blind receipt bills nothing here: it has no PO line, and a PO attached
 * to it afterwards is a reference only (BR-RCP-002).</p>
 */
@Repository
class InvoiceMatchFactsAdapter implements InvoiceMatchFacts {

    private static final String POSTED = "r.status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED')";
    private static final String BILLED = "i.status IN ('MATCHED', 'PAID')";

    /**
     * Per receipt line: units received, and those QC accepted and rejected. A line without QC is billable as received;
     * on a line with QC only what was accepted is, and what is neither accepted nor rejected - not inspected, or held
     * in quarantine for a second decision - awaits QC.
     */
    private static final String RECEIPT_LINES = """
            SELECT l.id, l.po_line_id, l.received_qty, l.qc_required,
                   COALESCE((SELECT SUM(q.quantity) FROM procurement.qc_inspections q
                              WHERE q.receipt_line_id = l.id AND q.outcome = 'ACCEPTED'), 0) AS accepted,
                   COALESCE((SELECT SUM(q.quantity) FROM procurement.qc_inspections q
                              WHERE q.receipt_line_id = l.id AND q.outcome = 'REJECTED'), 0) AS rejected
              FROM procurement.goods_receipt_lines l
              JOIN procurement.goods_receipts r ON r.id = l.receipt_id
             WHERE""" + " " + POSTED;

    private final EntityManager entityManager;
    private final InventoryService inventory;

    InvoiceMatchFactsAdapter(EntityManager entityManager, InventoryService inventory) {
        this.entityManager = entityManager;
        this.inventory = inventory;
    }

    @Override
    public Map<UUID, OrderLine> orderLines(Collection<UUID> poLineIds) {
        if (poLineIds.isEmpty()) {
            return Map.of();
        }
        return orderLinesWhere("pl.id IN (:ids)", "ids", Set.copyOf(poLineIds)).stream()
                .collect(Collectors.toMap(OrderLine::poLineId, line -> line));
    }

    @Override
    public List<OrderLine> orderLinesOf(UUID purchaseOrderId) {
        return orderLinesWhere("pl.po_id = :po", "po", purchaseOrderId);
    }

    private List<OrderLine> orderLinesWhere(String condition, String parameter, Object value) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT pl.id, p.id, p.po_number, p.supplier_id, p.po_type, p.status, p.currency, pl.line_no,
                               pl.inventory_item_id, pl.ordered_qty, pl.unit_price, pl.discount_rate, pl.tax_rate
                          FROM procurement.purchase_order_lines pl
                          JOIN procurement.purchase_orders p ON p.id = pl.po_id
                         WHERE %s
                         ORDER BY p.po_number, pl.line_no""".formatted(condition))
                .setParameter(parameter, value)
                .getResultList();
        Map<UUID, String> skus = inventory.skusOf(rows.stream().map(r -> (UUID) r[8]).collect(Collectors.toSet()));
        return rows.stream().map(r -> new OrderLine((UUID) r[0], (UUID) r[1], (String) r[2], (UUID) r[3],
                PurchaseOrder.Type.valueOf((String) r[4]), PurchaseOrderStatus.valueOf((String) r[5]), (String) r[6],
                ((Number) r[7]).intValue(), skus.getOrDefault((UUID) r[8], "UNKNOWN"),
                ((BigDecimal) r[9]).intValueExact(), (BigDecimal) r[10], (BigDecimal) r[11], (BigDecimal) r[12]))
                .toList();
    }

    @Override
    public Map<UUID, UUID> poLinesOfReceiptLines(Collection<UUID> receiptLineIds) {
        if (receiptLineIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> poLines = new HashMap<>();
        rows("SELECT id, po_line_id FROM procurement.goods_receipt_lines WHERE id IN (:ids) AND po_line_id IS NOT NULL",
                receiptLineIds).forEach(r -> poLines.put((UUID) r[0], (UUID) r[1]));
        return poLines;
    }

    @Override
    public void lockOrders(Collection<UUID> purchaseOrderIds) {
        if (purchaseOrderIds.isEmpty()) {
            return;
        }
        entityManager.createNativeQuery("""
                        SELECT id FROM procurement.purchase_orders WHERE id IN (:ids) ORDER BY id FOR UPDATE""")
                .setParameter("ids", Set.copyOf(purchaseOrderIds))
                .getResultList();
    }

    @Override
    public Map<UUID, Received> receivedOnPoLines(Collection<UUID> poLineIds) {
        return received(poLineIds, "l.po_line_id IN (:ids)", 1);
    }

    @Override
    public Map<UUID, Received> receivedOnReceiptLines(Collection<UUID> receiptLineIds) {
        return received(receiptLineIds, "l.id IN (:ids)", 0);
    }

    /** Sums {@link #RECEIPT_LINES} by the column at {@code keyColumn}: 0 the receipt line, 1 its PO line. */
    private Map<UUID, Received> received(Collection<UUID> ids, String condition, int keyColumn) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, int[]> sums = new HashMap<>();
        for (Object[] r : rows(RECEIPT_LINES + " AND " + condition, ids)) {
            int receivedQty = ((Number) r[2]).intValue();
            boolean qc = (Boolean) r[3];
            int accepted = ((Number) r[4]).intValue();
            int rejected = ((Number) r[5]).intValue();
            int[] sum = sums.computeIfAbsent((UUID) r[keyColumn], key -> new int[2]);
            sum[0] += qc ? accepted : receivedQty;
            sum[1] += qc ? Math.max(receivedQty - accepted - rejected, 0) : 0;
        }
        Map<UUID, Received> received = new HashMap<>();
        sums.forEach((key, sum) -> received.put(key, new Received(sum[0], sum[1])));
        return received;
    }

    @Override
    public Map<UUID, Integer> invoicedOnPoLines(Collection<UUID> poLineIds, UUID excludingInvoice) {
        return invoiced("x.po_line_id", poLineIds, excludingInvoice);
    }

    @Override
    public Map<UUID, Integer> invoicedOnReceiptLines(Collection<UUID> receiptLineIds, UUID excludingInvoice) {
        return invoiced("x.receipt_line_id", receiptLineIds, excludingInvoice);
    }

    private Map<UUID, Integer> invoiced(String column, Collection<UUID> ids, UUID excludingInvoice) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT %1$s, SUM(x.invoiced_qty)
                          FROM procurement.supplier_invoice_lines x
                          JOIN procurement.supplier_invoices i ON i.id = x.invoice_id
                         WHERE %1$s IN (:ids) AND %2$s AND i.id <> :excluding
                         GROUP BY %1$s""".formatted(column, BILLED))
                .setParameter("ids", Set.copyOf(ids))
                .setParameter("excluding", excludingInvoice)
                .getResultList();
        Map<UUID, Integer> invoiced = new HashMap<>();
        rows.forEach(r -> invoiced.put((UUID) r[0], ((BigDecimal) r[1]).intValue()));
        return invoiced;
    }

    @Override
    public BigDecimal invoicedAmountOnOrder(UUID purchaseOrderId, UUID excludingInvoice) {
        Object amount = entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(x.invoiced_qty * x.unit_price), 0)
                          FROM procurement.supplier_invoice_lines x
                          JOIN procurement.supplier_invoices i ON i.id = x.invoice_id
                          JOIN procurement.purchase_order_lines pl ON pl.id = x.po_line_id
                         WHERE pl.po_id = :po AND %s AND i.id <> :excluding""".formatted(BILLED))
                .setParameter("po", purchaseOrderId)
                .setParameter("excluding", excludingInvoice)
                .getSingleResult();
        return ((BigDecimal) amount).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Collection<UUID> ids) {
        return entityManager.createNativeQuery(sql).setParameter("ids", Set.copyOf(ids)).getResultList();
    }
}

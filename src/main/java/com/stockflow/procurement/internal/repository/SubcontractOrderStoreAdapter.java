package com.stockflow.procurement.internal.repository;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Native SQL over {@code procurement.purchase_orders} / {@code purchase_order_lines} /
 * {@code purchase_order_events} / {@code suppliers}, this module's own schema; see
 * {@link SubcontractOrderStore}. Tax is zero on the print work (it is invoiced by the subcontractor
 * and matched at invoice time), so subtotal, net and total are the same amount.
 */
@Repository
class SubcontractOrderStoreAdapter implements SubcontractOrderStore {

    private static final String SELECT = """
            SELECT p.id, p.po_number, p.production_order_id, p.supplier_id, p.warehouse_id, p.status,
                   l.inventory_item_id, l.ordered_qty, l.unit_price, p.currency, p.total_amount, p.expected_date
              FROM procurement.purchase_orders p
              JOIN procurement.purchase_order_lines l ON l.po_id = p.id
             WHERE p.po_type = 'SUBCONTRACT'
            """;

    private final EntityManager entityManager;

    SubcontractOrderStoreAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Row> findLiveByProductionOrder(UUID productionOrderId) {
        return first(entityManager.createNativeQuery(SELECT
                        + " AND p.production_order_id = :po AND p.status <> 'CANCELLED'")
                .setParameter("po", productionOrderId)
                .getResultList());
    }

    @Override
    public Optional<Row> findById(UUID purchaseOrderId, boolean forUpdate) {
        return first(entityManager.createNativeQuery(SELECT + " AND p.id = :id" + (forUpdate ? " FOR UPDATE OF p" : ""))
                .setParameter("id", purchaseOrderId)
                .getResultList());
    }

    @Override
    public Optional<Supplier> supplier(UUID supplierId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT code, name, is_print_subcontractor, loss_tolerance_percent
                          FROM procurement.suppliers WHERE id = :id""")
                .setParameter("id", supplierId)
                .getResultList();
        return rows.stream().findFirst().map(r -> new Supplier(supplierId, (String) r[0], (String) r[1],
                (Boolean) r[2], (BigDecimal) r[3]));
    }

    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('SPO', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "SPO-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    @Override
    public void insert(NewOrder o) {
        BigDecimal total = o.unitPrice().multiply(BigDecimal.valueOf(o.quantity()));
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_orders
                               (id, po_number, po_type, production_order_id, status, supplier_id, warehouse_id,
                                currency, order_date, expected_date, subtotal, total_amount, created_at, created_by)
                        VALUES (:id, :number, 'SUBCONTRACT', :po, 'DRAFT', :supplier, :warehouse, :currency,
                                :orderDate, :expected, :total, :total, NOW(), 'subcontracting')""")
                .setParameter("id", o.id())
                .setParameter("number", o.poNumber())
                .setParameter("po", o.productionOrderId())
                .setParameter("supplier", o.supplierId())
                .setParameter("warehouse", o.warehouseId())
                .setParameter("currency", o.currency())
                .setParameter("orderDate", Date.valueOf(o.orderDate()))
                .setParameter("expected", o.expectedDate() == null ? null : Date.valueOf(o.expectedDate()))
                .setParameter("total", total)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_lines
                               (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, tax_rate,
                                line_subtotal, line_net, line_total, created_at, created_by)
                        VALUES (:id, :po, 1, :item, :qty, :price, 0, :total, :total, :total, NOW(), 'subcontracting')""")
                .setParameter("id", Identifiers.newId())
                .setParameter("po", o.id())
                .setParameter("item", o.inventoryItemId())
                .setParameter("qty", BigDecimal.valueOf(o.quantity()))
                .setParameter("price", o.unitPrice())
                .setParameter("total", total)
                .executeUpdate();
        event(o.id(), "CREATED", null, "DRAFT", o.createdBy(), "Raised for production order " + o.productionOrderId());
    }

    @Override
    public void changeQuantity(Row order, int newQuantity, String reason, UUID actorId) {
        BigDecimal total = order.unitPrice().multiply(BigDecimal.valueOf(newQuantity));
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_order_lines
                           SET ordered_qty = :qty, line_subtotal = :total, line_net = :total, line_total = :total,
                               version = version + 1, last_modified_at = NOW(), last_modified_by = 'subcontracting'
                         WHERE po_id = :po""")
                .setParameter("qty", BigDecimal.valueOf(newQuantity))
                .setParameter("total", total)
                .setParameter("po", order.id())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_orders
                           SET subtotal = :total, total_amount = :total, version = version + 1,
                               last_modified_at = NOW(), last_modified_by = 'subcontracting'
                         WHERE id = :po""")
                .setParameter("total", total)
                .setParameter("po", order.id())
                .executeUpdate();
        event(order.id(), "REVISED", order.status(), order.status(), actorId, reason);
    }

    private void event(UUID po, String action, String from, String to, UUID actor, String reason) {
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_events
                               (id, po_id, action, actor_id, from_status, to_status, reason, created_at, created_by)
                        VALUES (:id, :po, :action, :actor, :from, :to, :reason, NOW(), 'subcontracting')""")
                .setParameter("id", Identifiers.newId())
                .setParameter("po", po)
                .setParameter("action", action)
                .setParameter("actor", actor)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("reason", reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Row> first(List<?> rows) {
        return ((List<Object[]>) rows).stream().findFirst().map(r -> new Row((UUID) r[0], (String) r[1],
                (UUID) r[2], (UUID) r[3], (UUID) r[4], (String) r[5], (UUID) r[6], (BigDecimal) r[7],
                (BigDecimal) r[8], (String) r[9], (BigDecimal) r[10],
                toLocalDate(r[11])));
    }

    private static LocalDate toLocalDate(Object value) {
        return value instanceof LocalDate date ? date : value == null ? null : ((Date) value).toLocalDate();
    }
}

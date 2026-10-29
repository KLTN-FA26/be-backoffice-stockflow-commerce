package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.domain.Sku;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.entity.PoLineJpaEntity;
import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SubcontractOrderStore} on the purchase order's own JPA entities: the order is built by the
 * {@link PurchaseOrder#subcontract} factory and stored the way every order is, so the aggregate's
 * checks, the {@code @Version} column and the audit columns hold for it too. Only the document number
 * sequence and the timeline event are SQL, both shared with the rest of the module.
 */
@Repository
class SubcontractOrderStoreAdapter implements SubcontractOrderStore {

    private final PurchaseOrderJpaRepository orders;
    private final SupplierJpaRepository suppliers;
    private final PurchaseOrderEventLog events;
    private final PoLineUnits lineUnits;

    @PersistenceContext
    private EntityManager entityManager;

    SubcontractOrderStoreAdapter(PurchaseOrderJpaRepository orders, SupplierJpaRepository suppliers,
                                 PurchaseOrderEventLog events, PoLineUnits lineUnits) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.events = events;
        this.lineUnits = lineUnits;
    }

    @Override
    public Optional<Row> findLiveByProductionOrder(UUID productionOrderId) {
        return orders.findByTypeAndProductionOrderIdAndStatusNot(PurchaseOrder.Type.SUBCONTRACT,
                productionOrderId, PurchaseOrderStatus.CANCELLED).map(SubcontractOrderStoreAdapter::toRow);
    }

    /** {@code forUpdate}: locked, then re-read — the context's copy may predate the lock. */
    @Override
    public Optional<Row> findById(UUID purchaseOrderId, boolean forUpdate) {
        Optional<PurchaseOrderJpaEntity> order = forUpdate
                ? orders.findByIdForUpdate(purchaseOrderId).map(locked -> {
                    entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
                    locked.getLines().forEach(entityManager::refresh);
                    return locked;
                })
                : orders.findWithLinesById(purchaseOrderId);
        return order.filter(o -> o.getType() == PurchaseOrder.Type.SUBCONTRACT).map(SubcontractOrderStoreAdapter::toRow);
    }

    @Override
    public Optional<Supplier> supplier(UUID supplierId) {
        return suppliers.findById(supplierId).map(s -> new Supplier(s.getId(), s.getCode(), s.getName(),
                s.isPrintSubcontractor(), s.getLossTolerancePercent(), s.getPaymentTermDays(), s.getLeadTimeDays()));
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
    public UUID insert(NewOrder o) {
        Currency currency = Currency.getInstance(o.currency());
        PoLine line = PurchaseOrder.line(1, o.inventoryItemId(), new Sku(o.sku()), o.uom(), null, o.quantity(),
                new Money(o.unitPrice(), currency), BigDecimal.ZERO);
        PurchaseOrder order = PurchaseOrder.subcontract(o.poNumber(), o.productionOrderId(), o.supplier().id(),
                o.warehouseId(), currency, line, o.orderDate(), o.expectedDate(), o.supplier().paymentTermDays(),
                o.supplier().leadTimeDays());
        PurchaseOrderJpaEntity entity = new PurchaseOrderJpaEntity(order);
        entity.addLine(new PoLineJpaEntity(line));
        entity.apply(order);
        orders.saveAndFlush(entity);
        events.record(entity.getId(), null, "CREATED", o.createdBy(), null, PurchaseOrderStatus.DRAFT.name(),
                "Raised for production order " + o.productionOrderId(), null, "subcontracting");
        return entity.getId();
    }

    @Override
    public void changeQuantity(Row row, int newQuantity, String reason, UUID actorId) {
        PurchaseOrderJpaEntity order = orders.findWithLinesById(row.id()).orElseThrow(() ->
                new IllegalStateException("Subcontract order " + row.poNumber() + " vanished while it was being changed"));
        java.util.Map<UUID, Integer> unitFactors = lineUnits.factors(order.getLines());
        order.getLines().forEach(line -> line.reviseDraftQuantity(newQuantity, unitFactors.getOrDefault(line.getId(), 1)));
        order.recomputeDraftTotals();
        orders.saveAndFlush(order);
        events.record(order.getId(), null, "REVISED", actorId, row.status(), row.status(), reason, null,
                "subcontracting");
    }

    @Override
    public int receivedQuantity(UUID purchaseOrderId) {
        Number received = (Number) entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(l.received_qty), 0) FROM procurement.goods_receipt_lines l
                          JOIN procurement.goods_receipts r ON r.id = l.receipt_id
                          JOIN procurement.purchase_order_lines pl ON pl.id = l.po_line_id
                         WHERE pl.po_id = :po AND r.status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED')""")
                .setParameter("po", purchaseOrderId).getSingleResult();
        return received.intValue();
    }

    @Override
    public Optional<Amendment> pendingAmendment(UUID purchaseOrderId) {
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT r.id, r.revision_no, lr.ordered_qty, lr.line_total, r.changed_by, r.change_summary
                          FROM procurement.purchase_orders p
                          JOIN procurement.purchase_order_revisions r ON r.id = p.pending_revision_id
                          JOIN procurement.purchase_order_line_revisions lr ON lr.po_revision_id = r.id
                         WHERE p.id = :po AND r.kind = 'AMENDMENT' AND p.status <> 'PENDING_APPROVAL'""")
                .setParameter("po", purchaseOrderId).getResultList();
        return rows.stream().findFirst().map(row -> new Amendment((UUID) row[0], ((Number) row[1]).longValue(),
                ((BigDecimal) row[2]).intValueExact(), (BigDecimal) row[3], (UUID) row[4], (String) row[5]));
    }

    @Override
    public void requestAmendment(Row row, int newQuantity, String reason, UUID actorId) {
        entityManager.flush();
        // Subcontract lines are written in the base unit (SubcontractPurchasingImpl), but a line entered in a
        // packing unit keeps it, and its quantity stays a whole number of that unit (V20261020000400).
        // check_subcontract_po_single_line: exactly one line.
        PoLineJpaEntity line = orders.findWithLinesById(row.id()).orElseThrow().getLines().get(0);
        Integer unitFactor = lineUnits.factors(java.util.List.of(line)).get(line.getId());
        Integer unitQuantity = unitFactor == null ? null : PoLineJpaEntity.wholeUnits(row.poNumber(), newQuantity, unitFactor);
        UUID revisionId = Identifiers.newId();
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_revisions
                               (id, po_id, revision_no, kind, snapshot_header, change_summary, changed_at, changed_by,
                                created_at, created_by)
                        SELECT :id, p.id,
                               (SELECT COALESCE(MAX(revision_no), 0) + 1 FROM procurement.purchase_order_revisions
                                 WHERE po_id = p.id),
                               'AMENDMENT',
                               jsonb_build_object('supplierId', CAST(p.supplier_id AS text),
                                                  'warehouseId', CAST(p.warehouse_id AS text), 'currency', p.currency,
                                                  'orderDate', CAST(p.order_date AS text),
                                                  'expectedDate', CAST(p.expected_date AS text),
                                                  'paymentTermDays', p.payment_term_days, 'note', p.note,
                                                  'quantity', CAST(:qty AS integer),
                                                  'previousQuantity', CAST(:previous AS integer),
                                                  'subtotal', ROUND(CAST(:qty AS numeric) * l.unit_price, 2),
                                                  'taxTotal', ROUND(ROUND(CAST(:qty AS numeric) * l.unit_price, 2)
                                                                    * l.tax_rate / 100, 2)),
                               :reason, NOW(), :actor, NOW(), 'subcontracting'
                          FROM procurement.purchase_orders p
                          JOIN procurement.purchase_order_lines l ON l.po_id = p.id
                         WHERE p.id = :po""")
                .setParameter("id", revisionId)
                .setParameter("po", row.id())
                .setParameter("qty", newQuantity)
                .setParameter("previous", row.quantity().intValueExact())
                .setParameter("reason", reason.length() > 500 ? reason.substring(0, 500) : reason)
                .setParameter("actor", actorId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_line_revisions
                               (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty, unit_price,
                                tax_rate, discount_rate, line_subtotal, line_discount_amount, line_net, line_tax,
                                line_total, note, uom_conversion_id, unit_qty)
                        SELECT :id, l.id, :revision, l.inventory_item_id, l.uom, :qty, l.unit_price, l.tax_rate, 0,
                               s.subtotal, 0, s.subtotal, s.tax, s.subtotal + s.tax, l.note,
                               l.uom_conversion_id, CAST(:unitQty AS integer)
                          FROM procurement.purchase_order_lines l
                         CROSS JOIN LATERAL (SELECT ROUND(CAST(:qty AS numeric) * l.unit_price, 2) AS subtotal) b
                         CROSS JOIN LATERAL (SELECT b.subtotal, ROUND(b.subtotal * l.tax_rate / 100, 2) AS tax) s
                         WHERE l.po_id = :po""")
                .setParameter("id", Identifiers.newId())
                .setParameter("revision", revisionId)
                .setParameter("qty", newQuantity)
                .setParameter("unitQty", unitQuantity)
                .setParameter("po", row.id())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_orders SET pending_revision_id = :revision, version = version + 1,
                               last_modified_at = NOW() WHERE id = :po""")
                .setParameter("revision", revisionId).setParameter("po", row.id()).executeUpdate();
        events.record(row.id(), revisionId, "REVISED", actorId, row.status(), row.status(), reason,
                java.util.Map.of("quantity", Integer.toString(newQuantity),
                        "previousQuantity", row.quantity().toBigInteger().toString()), "subcontracting");
        refresh(row.id());
    }

    @Override
    public void approveAmendment(Row row, Amendment amendment, UUID approverId, AutoApproval auto) {
        entityManager.flush();
        // An amendment changes how many, never the unit: the line keeps its uom_conversion_id. A revision that
        // froze no unit for a line that has one (written before revisions carried it) then fails
        // ck_purchase_order_lines_unit instead of quietly moving the line back to the base unit.
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_order_lines l
                           SET ordered_qty = lr.ordered_qty, unit_qty = lr.unit_qty,
                               line_subtotal = lr.line_subtotal, line_net = lr.line_net,
                               line_tax = lr.line_tax, line_total = lr.line_total, po_revision_id = lr.po_revision_id,
                               status = CASE WHEN l.status = 'RECEIVED' THEN 'PARTIALLY_RECEIVED' ELSE l.status END,
                               version = l.version + 1, last_modified_at = NOW()
                          FROM procurement.purchase_order_line_revisions lr
                         WHERE lr.po_revision_id = :revision AND lr.po_line_id = l.id""")
                .setParameter("revision", amendment.revisionId()).executeUpdate();
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_orders p
                           SET active_revision_id = :revision, pending_revision_id = NULL, revision_no = :no,
                               subtotal = t.subtotal, tax_total = t.tax,
                               total_amount = t.subtotal - p.discount_total + t.tax + p.shipping_fee,
                               status = CASE WHEN p.status = 'RECEIVED' THEN 'PARTIALLY_RECEIVED' ELSE p.status END,
                               version = p.version + 1, last_modified_at = NOW()
                          FROM (SELECT SUM(line_subtotal) AS subtotal, SUM(line_tax) AS tax
                                  FROM procurement.purchase_order_lines WHERE po_id = :po AND status <> 'WITHDRAWN') t
                         WHERE p.id = :po""")
                .setParameter("revision", amendment.revisionId()).setParameter("no", amendment.revisionNo())
                .setParameter("po", row.id()).executeUpdate();
        String to = "RECEIVED".equals(row.status()) ? "PARTIALLY_RECEIVED" : row.status();
        if (auto == null) {
            approval(amendment.revisionId(), approverId, "APPROVED", null);
            events.record(row.id(), amendment.revisionId(), "APPROVED", approverId, row.status(), to,
                    amendment.reason(), java.util.Map.of("quantity", Integer.toString(amendment.quantity())),
                    "subcontracting");
        } else {
            autoApproval(amendment.revisionId(), approverId, auto);
            events.record(row.id(), amendment.revisionId(), "AUTO_APPROVED", approverId, row.status(), to,
                    auto.reason(), auto.facts(amendment.total(), amendment.quantity()), "subcontracting");
        }
        refresh(row.id());
    }

    @Override
    public void dropAmendment(Row row, Amendment amendment, UUID actorId, String decision, String reason) {
        entityManager.flush();
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_orders SET pending_revision_id = NULL, version = version + 1,
                               last_modified_at = NOW() WHERE id = :po""")
                .setParameter("po", row.id()).executeUpdate();
        approval(amendment.revisionId(), actorId, decision, reason);
        events.record(row.id(), amendment.revisionId(), decision, actorId, row.status(), row.status(),
                reason, null, "subcontracting");
        refresh(row.id());
    }

    private void approval(UUID revisionId, UUID approverId, String decision, String reason) {
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_approvals
                               (id, po_revision_id, step_no, approver_id, decision, decision_at, reason, created_at, created_by)
                        VALUES (:id, :revision,
                                (SELECT COALESCE(MAX(step_no), 0) + 1 FROM procurement.purchase_order_approvals
                                  WHERE po_revision_id = :revision),
                                :approver, :decision, NOW(), :reason, NOW(), 'subcontracting')""")
                .setParameter("id", Identifiers.newId())
                .setParameter("revision", revisionId)
                .setParameter("approver", approverId)
                .setParameter("decision", decision)
                .setParameter("reason", reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason)
                .executeUpdate();
    }

    private void autoApproval(UUID revisionId, UUID approverId, AutoApproval auto) {
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_approvals
                               (id, po_revision_id, step_no, approver_id, decision, decision_at, reason,
                                limit_id, limit_amount, limit_role_id, limit_holder_id, created_at, created_by)
                        VALUES (:id, :revision,
                                (SELECT COALESCE(MAX(step_no), 0) + 1 FROM procurement.purchase_order_approvals
                                  WHERE po_revision_id = :revision),
                                :approver, 'AUTO_APPROVED', :at, :reason,
                                :limitId, :limitAmount, :limitRole, :holder, NOW(), 'subcontracting')""")
                .setParameter("id", Identifiers.newId())
                .setParameter("revision", revisionId)
                .setParameter("approver", approverId)
                .setParameter("at", auto.at())
                .setParameter("reason", auto.reason().length() > 500 ? auto.reason().substring(0, 500) : auto.reason())
                .setParameter("limitId", auto.limit().id())
                .setParameter("limitAmount", auto.limit().amount().amount())
                .setParameter("limitRole", auto.limit().roleId())
                .setParameter("holder", auto.limitHolderId())
                .executeUpdate();
    }

    /** The order and its lines as SQL left them, for whoever reads them through JPA later in the transaction. */
    private void refresh(UUID purchaseOrderId) {
        orders.findById(purchaseOrderId).ifPresent(order -> {
            entityManager.refresh(order);
            order.getLines().forEach(entityManager::refresh);
        });
    }

    private static final String SETTLEMENT_COLUMNS = """
            po_id, production_order_id, status, currency, unit_price, received_quantity, passed_quantity,
            failed_quantity, payable_amount, blanks_sent, blanks_used, blanks_returned, blanks_lost, tolerance_percent,
            tolerance_blanks, excess_loss, deduction_per_blank, deduction_amount, cancellation_fee,
            cancellation_fee_reason, net_payable, settled_at, settled_by, approved_at, approved_by, approval_note""";

    @Override
    public Optional<com.stockflow.procurement.api.SubcontractSettlement> settlement(UUID purchaseOrderId,
                                                                                   boolean forUpdate) {
        entityManager.flush();
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = entityManager.createNativeQuery("SELECT " + SETTLEMENT_COLUMNS
                        + " FROM procurement.subcontract_settlement WHERE po_id = :po" + (forUpdate ? " FOR UPDATE" : ""))
                .setParameter("po", purchaseOrderId).getResultList();
        return rows.stream().findFirst().map(r -> new com.stockflow.procurement.api.SubcontractSettlement(
                (UUID) r[0], (UUID) r[1], (String) r[2], (String) r[3], (BigDecimal) r[4], ((Number) r[5]).intValue(),
                ((Number) r[6]).intValue(), ((Number) r[7]).intValue(), (BigDecimal) r[8], ((Number) r[9]).intValue(),
                ((Number) r[10]).intValue(), ((Number) r[11]).intValue(), ((Number) r[12]).intValue(),
                (BigDecimal) r[13], ((Number) r[14]).intValue(), ((Number) r[15]).intValue(), (BigDecimal) r[16],
                (BigDecimal) r[17], (BigDecimal) r[18], (String) r[19], (BigDecimal) r[20], instant(r[21]),
                (UUID) r[22], instant(r[23]), (UUID) r[24], (String) r[25]));
    }

    private static java.time.Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case java.time.Instant i -> i;
            case java.time.OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Not a timestamp: " + value.getClass());
        };
    }

    @Override
    public void insertSettlement(com.stockflow.procurement.api.SubcontractSettlement s) {
        entityManager.flush();
        entityManager.createNativeQuery("INSERT INTO procurement.subcontract_settlement (" + SETTLEMENT_COLUMNS + """
                        ) VALUES (:po, :production, :status, :currency, :price, :received, :passed, :failed, :payable,
                                  :sent, :used, :returned, :lost, :tolerancePercent, :toleranceBlanks, :excess, NULL, 0,
                                  :fee, CAST(:feeReason AS varchar), :net, :at, CAST(:by AS uuid), NULL, NULL, NULL)""")
                .setParameter("po", s.purchaseOrderId())
                .setParameter("production", s.productionOrderId())
                .setParameter("status", s.status())
                .setParameter("currency", s.currency())
                .setParameter("price", s.unitPrice())
                .setParameter("received", s.receivedQuantity())
                .setParameter("passed", s.passedQuantity())
                .setParameter("failed", s.failedQuantity())
                .setParameter("payable", s.payableAmount())
                .setParameter("sent", s.blanksSent())
                .setParameter("used", s.blanksUsed())
                .setParameter("returned", s.blanksReturned())
                .setParameter("lost", s.blanksLost())
                .setParameter("tolerancePercent", s.tolerancePercent())
                .setParameter("toleranceBlanks", s.toleranceBlanks())
                .setParameter("excess", s.excessLoss())
                .setParameter("fee", s.cancellationFee())
                .setParameter("feeReason", s.cancellationFeeReason())
                .setParameter("net", s.netPayable())
                .setParameter("at", s.settledAt())
                .setParameter("by", s.settledBy())
                .executeUpdate();
    }

    @Override
    public void approveSettlement(UUID purchaseOrderId, BigDecimal deductionPerBlank, BigDecimal deductionAmount,
                                  BigDecimal netPayable, String note, UUID approverId, java.time.Instant at) {
        entityManager.createNativeQuery("""
                        UPDATE procurement.subcontract_settlement
                           SET status = 'FINAL', deduction_per_blank = :perBlank, deduction_amount = :amount,
                               net_payable = :net, approval_note = CAST(:note AS varchar), approved_by = :approver,
                               approved_at = :at,
                               version = version + 1
                         WHERE po_id = :po AND status = 'AWAITING_APPROVAL'""")
                .setParameter("perBlank", deductionPerBlank)
                .setParameter("amount", deductionAmount)
                .setParameter("net", netPayable)
                .setParameter("note", note)
                .setParameter("approver", approverId)
                .setParameter("at", at)
                .setParameter("po", purchaseOrderId)
                .executeUpdate();
    }

    private static Row toRow(PurchaseOrderJpaEntity order) {
        // check_subcontract_po_single_line: exactly one line.
        PoLineJpaEntity line = order.getLines().get(0);
        return new Row(order.getId(), order.getPoNumber(), order.getProductionOrderId(), order.getSupplierId(),
                order.getWarehouseId(), order.getStatus().name(), line.getInventoryItemId(), line.getOrderedQuantity(),
                line.getUnitPrice(), order.getCurrency(), order.getTotalAmount(), order.getExpectedAt());
    }
}

package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.SupplierSpendSummary;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.entity.PoLineJpaEntity;
import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adapter: implements the aggregate's domain port plus the list-only search and reporting
 * interfaces, on {@code procurement.purchase_orders} and its lines.
 *
 * <p>Two things a line needs are not on its row: its SKU, which is the inventory item's (read through
 * {@code inventory :: api} in one call per order or page), and the quantity received, which is the sum
 * of the confirmed goods receipt lines against it (this module's own tables).</p>
 */
@Repository
class PurchaseOrderRepositoryAdapter
        implements PurchaseOrderRepository, PurchaseOrderSearchRepository, ProcurementReportRepository {

    private static final DateTimeFormatter PO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Not a commitment yet, or never one: they are not spend (SCRUM-119). */
    private static final List<PurchaseOrderStatus> NON_SPEND_STATUSES = List.of(PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.CANCELLED);

    private final PurchaseOrderJpaRepository jpa;
    private final SupplierJpaRepository suppliers;
    private final PoNumberSequence poNumberSequence;
    private final InventoryService inventory;
    private final WarehouseService warehouses;
    private final PurchaseOrderEventLog events;

    @PersistenceContext
    private EntityManager entityManager;

    PurchaseOrderRepositoryAdapter(PurchaseOrderJpaRepository jpa, SupplierJpaRepository suppliers,
                                   PoNumberSequence poNumberSequence, InventoryService inventory,
                                   WarehouseService warehouses, PurchaseOrderEventLog events) {
        this.jpa = jpa;
        this.suppliers = suppliers;
        this.poNumberSequence = poNumberSequence;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.events = events;
    }

    // ------------------------------------------------------------------ aggregate

    @Override
    public Optional<PurchaseOrder> findById(PurchaseOrderId id) {
        return jpa.findWithLinesById(id.value()).map(this::toDomain);
    }

    /**
     * Locked and re-read: the lock is taken by a query, and the entity this persistence context may
     * already hold was read before it — deciding on that copy would make the lock decorative.
     */
    @Override
    public Optional<PurchaseOrder> findByIdForUpdate(PurchaseOrderId id) {
        return jpa.findByIdForUpdate(id.value()).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            entity.getLines().forEach(entityManager::refresh);
            return toDomain(entity);
        });
    }

    @Override
    public boolean existsById(PurchaseOrderId id) {
        return jpa.existsById(id.value());
    }

    @Override
    public String nextPoNumber(LocalDate date) {
        return "PO-%s-%06d".formatted(PO_DATE.format(date), poNumberSequence.nextFor(date));
    }

    @Override
    public Optional<SupplierStatus> supplierStatus(UUID supplierId) {
        return suppliers.findById(supplierId).map(SupplierJpaEntity::getStatus);
    }

    @Override
    public List<PurchaseOrder> findOpenBySupplierAndExpectedAt(UUID supplierId, LocalDate expectedAt) {
        return jpa.findBySupplierIdAndExpectedAtAndStatusNotIn(supplierId, expectedAt,
                        List.of(PurchaseOrderStatus.CANCELLED, PurchaseOrderStatus.CLOSED, PurchaseOrderStatus.RECEIVED))
                .stream().map(this::toDomain).toList();
    }

    /**
     * Insert or update. A new order is inserted with its lines; afterwards only the header and the
     * line statuses change — what was ordered is changed by a new revision, not in place.
     */
    @Override
    public PurchaseOrder save(PurchaseOrder order) {
        Optional<PurchaseOrderJpaEntity> existing = jpa.findWithLinesById(order.id().value());
        PurchaseOrderJpaEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            Map<UUID, PoLine> byId = order.lines().stream().collect(Collectors.toMap(PoLine::id, Function.identity()));
            for (PoLineJpaEntity line : entity.getLines()) {
                PoLine current = byId.get(line.getId());
                if (current != null) {
                    line.setStatus(current.status());
                }
            }
        } else {
            entity = new PurchaseOrderJpaEntity(order);
            for (PoLine line : order.lines()) {
                entity.addLine(new PoLineJpaEntity(line));
            }
        }
        entity.apply(order);
        // saveAndFlush, not save: the audit columns are set at flush time, and a revision snapshot
        // taken right after needs the lines in the database.
        return toDomain(jpa.saveAndFlush(entity));
    }

    @Override
    public boolean hasReceiptInProgress(PurchaseOrderId id) {
        return (Boolean) entityManager.createNativeQuery("""
                        SELECT EXISTS (SELECT 1 FROM procurement.goods_receipts WHERE po_id = :po AND status = 'DRAFT')""")
                .setParameter("po", id.value())
                .getSingleResult();
    }

    // ------------------------------------------------------------------ history (append-only)

    @Override
    public long nextRevisionNo(PurchaseOrderId id) {
        return ((Number) entityManager.createNativeQuery("""
                        SELECT COALESCE(MAX(revision_no) + 1, 0) FROM procurement.purchase_order_revisions WHERE po_id = :po""")
                .setParameter("po", id.value())
                .getSingleResult()).longValue();
    }

    @Override
    public UUID recordRevision(PurchaseOrder order, long revisionNo, UUID actorId, String changeSummary) {
        UUID revisionId = Identifiers.newId();
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_revisions
                               (id, po_id, revision_no, kind, snapshot_header, change_summary, changed_at, changed_by,
                                created_at, created_by)
                        VALUES (:id, :po, :no, :kind,
                                jsonb_build_object('supplierId', CAST(:supplier AS text), 'warehouseId', CAST(:warehouse AS text),
                                                   'currency', CAST(:currency AS text), 'orderDate', CAST(:orderDate AS text),
                                                   'expectedDate', CAST(:expected AS text), 'subtotal', CAST(:subtotal AS numeric),
                                                   'taxTotal', CAST(:tax AS numeric), 'totalAmount', CAST(:total AS numeric),
                                                   'paymentTermDays', CAST(:terms AS integer), 'note', CAST(:note AS text)),
                                :summary, NOW(), :actor, NOW(), 'procurement')""")
                .setParameter("id", revisionId)
                .setParameter("po", order.id().value())
                .setParameter("no", revisionNo)
                .setParameter("kind", revisionNo == 0 ? "INITIAL" : "AMENDMENT")
                .setParameter("supplier", order.supplierId().toString())
                .setParameter("warehouse", order.warehouseId().toString())
                .setParameter("currency", order.currency().getCurrencyCode())
                .setParameter("orderDate", order.orderDate().toString())
                .setParameter("expected", order.expectedAt() == null ? null : order.expectedAt().toString())
                .setParameter("subtotal", order.subtotal().amount())
                .setParameter("tax", order.taxTotal().amount())
                .setParameter("total", order.totalAmount().amount())
                .setParameter("terms", order.paymentTermDays())
                .setParameter("note", order.note())
                .setParameter("summary", revisionNo == 0 ? null
                        : changeSummary == null ? "Resubmitted" : truncate(changeSummary, 500))
                .setParameter("actor", actorId)
                .executeUpdate();
        for (PoLine line : order.lines()) {
            entityManager.createNativeQuery("""
                            INSERT INTO procurement.purchase_order_line_revisions
                                   (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty, unit_price,
                                    tax_rate, discount_rate, line_subtotal, line_discount_amount, line_net, line_tax,
                                    line_total, note)
                            VALUES (:id, :line, :revision, :item, :uom, :qty, :price, :taxRate, 0, :subtotal, 0,
                                    :subtotal, :tax, :total, :note)""")
                    .setParameter("id", Identifiers.newId())
                    .setParameter("line", line.id())
                    .setParameter("revision", revisionId)
                    .setParameter("item", line.inventoryItemId())
                    .setParameter("uom", line.uom())
                    .setParameter("qty", BigDecimal.valueOf(line.quantityOrdered()))
                    .setParameter("price", line.unitPrice().amount())
                    .setParameter("taxRate", line.taxRate())
                    .setParameter("subtotal", line.subtotal())
                    .setParameter("tax", line.tax())
                    .setParameter("total", line.total())
                    .setParameter("note", line.description())
                    .executeUpdate();
        }
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_order_lines SET po_revision_id = :revision WHERE po_id = :po""")
                .setParameter("revision", revisionId)
                .setParameter("po", order.id().value())
                .executeUpdate();
        return revisionId;
    }

    @Override
    public void recordApproval(UUID revisionId, UUID approverId, String decision, String reason) {
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_approvals
                               (id, po_revision_id, step_no, approver_id, decision, decision_at, reason, created_at, created_by)
                        VALUES (:id, :revision,
                                (SELECT COALESCE(MAX(step_no), 0) + 1 FROM procurement.purchase_order_approvals
                                  WHERE po_revision_id = :revision),
                                :approver, :decision, NOW(), :reason, NOW(), 'procurement')""")
                .setParameter("id", Identifiers.newId())
                .setParameter("revision", revisionId)
                .setParameter("approver", approverId)
                .setParameter("decision", decision)
                .setParameter("reason", reason == null ? null : truncate(reason, 500))
                .executeUpdate();
    }

    @Override
    public void recordEvent(PurchaseOrderId id, UUID revisionId, String action, UUID actorId, PurchaseOrderStatus from,
                            PurchaseOrderStatus to, String reason) {
        events.record(id.value(), revisionId, action, actorId, from == null ? null : from.name(),
                to == null ? null : to.name(), reason, null, "procurement");
    }

    // ------------------------------------------------------------------ search

    @Override
    public Page<PurchaseOrderSummary> search(PurchaseOrderSearchCriteria criteria, Pageable pageable) {
        Specification<PurchaseOrderJpaEntity> spec = Specification
                .<PurchaseOrderJpaEntity>where(Specs.eq("supplierId", criteria.supplierId()))
                .and(Specs.eq("warehouseId", criteria.warehouseId()))
                .and(Specs.in("status", criteria.statuses()))
                .and(Specs.contains("poNumber", criteria.search()))
                // BR-SEC-002: warehouse staff see the purchase orders of their own warehouses only.
                .and(com.stockflow.common.security.WarehouseScope.warehousesIn("warehouseId"));
        var page = jpa.findAll(spec, pageable);
        var suppliersById = suppliers.findAllById(page.getContent().stream()
                        .map(PurchaseOrderJpaEntity::getSupplierId).distinct().toList())
                .stream().collect(Collectors.toMap(SupplierJpaEntity::getId, Function.identity()));
        var warehousesById = new HashMap<UUID, Optional<WarehouseView>>();
        return page.map(order -> summary(order, suppliersById.get(order.getSupplierId()),
                warehousesById.computeIfAbsent(order.getWarehouseId(), warehouses::findWarehouse).orElse(null),
                List.of(), false));
    }

    @Override
    public Optional<PurchaseOrderSummary> summary(PurchaseOrderId id, boolean possibleDuplicate) {
        return jpa.findWithLinesById(id.value()).map(entity -> {
            var order = toDomain(entity);
            var lines = order.lines().stream().map(l -> new POLineSummary(l.id(), l.lineNo(), l.inventoryItemId(),
                    l.sku().code(), l.description(), l.uom(), l.quantityOrdered(), l.quantityReceived(),
                    l.unitPrice().amount(), l.taxRate(), l.total(), l.status().name())).toList();
            return summary(entity, suppliers.findById(entity.getSupplierId()).orElse(null),
                    warehouses.findWarehouse(entity.getWarehouseId()).orElse(null), lines, possibleDuplicate);
        });
    }

    private static PurchaseOrderSummary summary(PurchaseOrderJpaEntity e, SupplierJpaEntity supplier,
                                                WarehouseView warehouse, List<POLineSummary> lines,
                                                boolean possibleDuplicate) {
        return new PurchaseOrderSummary(e.getId(), e.getPoNumber(), e.getType().name(), e.getProductionOrderId(),
                e.getSupplierId(), supplier == null ? null : supplier.getCode(),
                supplier == null ? null : supplier.getName(), e.getWarehouseId(),
                warehouse == null ? null : warehouse.prefix(), warehouse == null ? null : warehouse.name(),
                e.getStatus().name(), e.getCurrency(), e.getOrderDate(), e.getExpectedAt(), e.getSubtotal(),
                e.getTaxTotal(), e.getTotalAmount(), e.getNote(), lines, e.getCreatedAt(), e.getCreatedBy(),
                e.getLastModifiedAt(), e.getLastModifiedBy(), possibleDuplicate, e.getRevisionNo(),
                e.getSubmittedBy(), e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(), e.getConfirmedBy(),
                e.getConfirmedAt(), e.getClosedAt(), e.getCloseKind() == null ? null : e.getCloseKind().name(),
                e.getCloseReason(), e.getCancelReason(), e.getPaymentTermDays(), e.getLeadTimeDays(),
                e.getConfirmedAt(), e.getSupplierConfirmationStatus().name(), e.getSupplierRespondedAt(),
                e.getSupplierReference(), e.getSupplierResponseNote());
    }

    // ------------------------------------------------------------------ reports

    @Override
    public List<PurchaseOrderStatusCount> statusDashboard() {
        Map<PurchaseOrderStatus, Long> counts = jpa.countByStatus().stream()
                .collect(Collectors.toMap(row -> (PurchaseOrderStatus) row[0], row -> (Long) row[1]));
        return Stream.of(PurchaseOrderStatus.values())
                .map(status -> new PurchaseOrderStatusCount(status.name(), counts.getOrDefault(status, 0L)))
                .toList();
    }

    /**
     * Built with the Criteria API rather than a static {@code @Query} string: a static JPQL
     * {@code (:x is null or col >= :x)} binds {@code :x} even when the filter is absent, and Postgres
     * cannot always infer that parameter's type from an {@code is null} check alone. Building the
     * predicate list in Java and never adding one for an absent filter sidesteps the whole problem.
     */
    @Override
    public Page<SupplierSpendSummary> supplierSpend(SupplierSpendCriteria criteria, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<SupplierSpendRow> query = cb.createQuery(SupplierSpendRow.class);
        Root<PurchaseOrderJpaEntity> root = query.from(PurchaseOrderJpaEntity.class);
        query.select(cb.construct(SupplierSpendRow.class, root.get("supplierId"), root.get("currency"),
                        cb.sum(root.get("totalAmount")), cb.count(root)))
                .where(spendPredicates(cb, root, criteria).toArray(new Predicate[0]))
                .groupBy(root.get("supplierId"), root.get("currency"))
                // Currency first: ranking 64,000,000 VND above 602.50 USD compares nothing.
                .orderBy(cb.asc(root.get("currency")), cb.desc(cb.sum(root.get("totalAmount"))),
                        cb.asc(root.get("supplierId")));
        List<SupplierSpendRow> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Object[]> keyQuery = cb.createQuery(Object[].class);
        Root<PurchaseOrderJpaEntity> keyRoot = keyQuery.from(PurchaseOrderJpaEntity.class);
        keyQuery.multiselect(keyRoot.get("supplierId"), keyRoot.get("currency"))
                .where(spendPredicates(cb, keyRoot, criteria).toArray(new Predicate[0]))
                .groupBy(keyRoot.get("supplierId"), keyRoot.get("currency"));
        long total = entityManager.createQuery(keyQuery).getResultList().size();

        Map<UUID, SupplierJpaEntity> suppliersById = suppliers
                .findAllById(content.stream().map(SupplierSpendRow::supplierId).toList()).stream()
                .collect(Collectors.toMap(SupplierJpaEntity::getId, Function.identity()));
        List<SupplierSpendSummary> summaries = content.stream().map(row -> {
            SupplierJpaEntity supplier = suppliersById.get(row.supplierId());
            return new SupplierSpendSummary(row.supplierId(), supplier.getCode(), supplier.getName(), row.currency(),
                    row.totalSpend(), row.orderCount());
        }).toList();
        return new PageImpl<>(summaries, pageable, total);
    }

    private List<Predicate> spendPredicates(CriteriaBuilder cb, Root<PurchaseOrderJpaEntity> root,
                                            SupplierSpendCriteria criteria) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.not(root.get("status").in(NON_SPEND_STATUSES)));
        if (criteria.supplierId() != null) {
            predicates.add(cb.equal(root.get("supplierId"), criteria.supplierId()));
        }
        if (criteria.currency() != null) {
            predicates.add(cb.equal(root.get("currency"), criteria.currency()));
        }
        if (criteria.expectedAtFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("expectedAt"), criteria.expectedAtFrom()));
        }
        if (criteria.expectedAtTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("expectedAt"), criteria.expectedAtTo()));
        }
        return predicates;
    }

    // ------------------------------------------------------------------ mapping

    private PurchaseOrder toDomain(PurchaseOrderJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        Map<UUID, String> skus = inventory.skusOf(e.getLines().stream().map(PoLineJpaEntity::getInventoryItemId)
                .collect(Collectors.toSet()));
        Map<UUID, Integer> received = receivedByLine(e.getId());
        List<PoLine> lines = e.getLines().stream().map(l -> new PoLine(l.getId(), l.getLineNo(), l.getInventoryItemId(),
                new Sku(skus.getOrDefault(l.getInventoryItemId(), "UNKNOWN")), l.getUom(), l.getDescription(),
                l.getOrderedQuantity().intValueExact(), received.getOrDefault(l.getId(), 0),
                new Money(l.getUnitPrice(), currency), l.getTaxRate(), l.getStatus())).toList();
        return new PurchaseOrder(new PurchaseOrder.State(new PurchaseOrderId(e.getId()), e.getPoNumber(), e.getType(),
                e.getProductionOrderId(), e.getSupplierId(), e.getWarehouseId(), e.getStatus(), currency, lines,
                e.getOrderDate(), e.getExpectedAt(), e.getNote(), e.getPaymentTermDays(), e.getLeadTimeDays(),
                e.getRevisionNo(), e.getActiveRevisionId(), e.getPendingRevisionId(), e.getSubmittedBy(),
                e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(), e.getConfirmedBy(), e.getConfirmedAt(),
                e.getClosedBy(), e.getClosedAt(), e.getCloseKind(), e.getCloseReason(), e.getCancelReason(),
                e.getSupplierConfirmationStatus(), e.getSupplierRespondedAt(), e.getSupplierReference(),
                e.getSupplierResponseNote(), e.getVersion(), e.getCreatedAt(), e.getCreatedBy(), e.getLastModifiedAt(),
                e.getLastModifiedBy()));
    }

    /** Counted in by a confirmed receipt (not a draft, not a cancelled one). */
    private Map<UUID, Integer> receivedByLine(UUID purchaseOrderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT l.po_line_id, SUM(l.received_qty)
                          FROM procurement.goods_receipt_lines l
                          JOIN procurement.goods_receipts r ON r.id = l.receipt_id
                         WHERE r.po_id = :po AND r.confirmed_at IS NOT NULL AND r.status <> 'CANCELLED'
                         GROUP BY l.po_line_id""")
                .setParameter("po", purchaseOrderId)
                .getResultList();
        Map<UUID, Integer> received = new HashMap<>();
        rows.forEach(r -> received.put((UUID) r[0], ((Number) r[1]).intValue()));
        return received;
    }

    private static String truncate(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}

package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.SupplierSpendSummary;
import com.stockflow.procurement.internal.domain.ApprovalLimit;
import com.stockflow.procurement.internal.domain.PackingUnit;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PoLineStatus;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final PoLineUnits lineUnits;

    @PersistenceContext
    private EntityManager entityManager;

    PurchaseOrderRepositoryAdapter(PurchaseOrderJpaRepository jpa, SupplierJpaRepository suppliers,
                                   PoNumberSequence poNumberSequence, InventoryService inventory,
                                   WarehouseService warehouses, PurchaseOrderEventLog events, PoLineUnits lineUnits) {
        this.jpa = jpa;
        this.suppliers = suppliers;
        this.poNumberSequence = poNumberSequence;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.events = events;
        this.lineUnits = lineUnits;
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
     * Insert or update. A new order is inserted with its lines. Afterwards the header and the line
     * statuses change; what was ordered changes in place only while the order is a DRAFT (SCRUM-462)
     * and from then on by a new revision. A line the draft no longer has is deleted, or kept as
     * WITHDRAWN when a revision already froze it — deleting it would take that revision's copy with it.
     */
    @Override
    public PurchaseOrder save(PurchaseOrder order) {
        Optional<PurchaseOrderJpaEntity> existing = jpa.findWithLinesById(order.id().value());
        PurchaseOrderJpaEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            Map<UUID, PoLine> byId = order.lines().stream().collect(Collectors.toMap(PoLine::id, Function.identity()));
            boolean draft = order.status() == PurchaseOrderStatus.DRAFT && entity.getStatus() == PurchaseOrderStatus.DRAFT;
            if (draft) {
                // Lines are rows of their own: an edit that touches only them would leave the order's
                // version where it was, and the next stale edit would pass the version check.
                entityManager.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            }
            Set<UUID> frozen = draft ? frozenLines(order.id().value()) : Set.of();
            Set<UUID> stored = new HashSet<>();
            for (PoLineJpaEntity line : List.copyOf(entity.getLines())) {
                if (line.getStatus() == PoLineStatus.WITHDRAWN) {
                    continue;
                }
                stored.add(line.getId());
                PoLine current = byId.get(line.getId());
                if (current == null && draft) {
                    if (frozen.contains(line.getId())) {
                        line.withdraw();
                    } else {
                        entity.removeLine(line);
                    }
                } else if (current != null) {
                    if (draft) {
                        line.reviseDraft(current);
                    }
                    line.setStatus(current.status());
                }
            }
            if (draft) {
                order.lines().stream().filter(line -> !stored.contains(line.id()))
                        .forEach(line -> entity.addLine(new PoLineJpaEntity(line)));
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
    public int nextLineNo(PurchaseOrderId id) {
        return ((Number) entityManager.createNativeQuery("""
                        SELECT COALESCE(MAX(line_no), 0) + 1 FROM procurement.purchase_order_lines WHERE po_id = :po""")
                .setParameter("po", id.value())
                .getSingleResult()).intValue();
    }

    /** Lines a revision has frozen ({@code po_revision_id}, written by {@link #recordRevision}, not mapped). */
    private Set<UUID> frozenLines(UUID purchaseOrderId) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("""
                        SELECT id FROM procurement.purchase_order_lines WHERE po_id = :po AND po_revision_id IS NOT NULL""")
                .setParameter("po", purchaseOrderId)
                .getResultList();
        return new HashSet<>(ids);
    }

    @Override
    public boolean hasReceiptInProgress(PurchaseOrderId id) {
        return (Boolean) entityManager.createNativeQuery("""
                        SELECT EXISTS (SELECT 1 FROM procurement.goods_receipts WHERE po_id = :po AND status IN ('DRAFT', 'PENDING_APPROVAL'))""")
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
        UUID revisionId = insertRevision(order, revisionNo, actorId,
                revisionNo == 0 ? null : changeSummary == null ? "Resubmitted" : truncate(changeSummary, 500),
                order.expectedAt(), order.note(), order.subtotal(), order.taxTotal(), order.totalAmount(), "{}");
        for (PoLine line : order.lines()) {
            insertLineRevision(revisionId, line);
        }
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_order_lines SET po_revision_id = :revision
                         WHERE po_id = :po AND status <> 'WITHDRAWN'""")
                .setParameter("revision", revisionId)
                .setParameter("po", order.id().value())
                .executeUpdate();
        return revisionId;
    }

    /**
     * The revision row: {@code snapshot_header} holds the header as the revision states it, plus {@code extraHeader}
     * (a JSON object, {@code "{}"} for nothing more) merged in.
     */
    private UUID insertRevision(PurchaseOrder order, long revisionNo, UUID actorId, String summary, LocalDate expectedAt,
                                String note, Money subtotal, Money taxTotal, Money totalAmount, String extraHeader) {
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
                                                   'paymentTermDays', CAST(:terms AS integer), 'note', CAST(:note AS text))
                                || CAST(:extra AS jsonb),
                                :summary, NOW(), :actor, NOW(), 'procurement')""")
                .setParameter("id", revisionId)
                .setParameter("po", order.id().value())
                .setParameter("no", revisionNo)
                .setParameter("kind", revisionNo == 0 ? "INITIAL" : "AMENDMENT")
                .setParameter("supplier", order.supplierId().toString())
                .setParameter("warehouse", order.warehouseId().toString())
                .setParameter("currency", order.currency().getCurrencyCode())
                .setParameter("orderDate", order.orderDate().toString())
                .setParameter("expected", expectedAt == null ? null : expectedAt.toString())
                .setParameter("subtotal", subtotal.amount())
                .setParameter("tax", taxTotal.amount())
                .setParameter("total", totalAmount.amount())
                .setParameter("terms", order.paymentTermDays())
                .setParameter("note", note)
                .setParameter("extra", extraHeader)
                .setParameter("summary", summary)
                .setParameter("actor", actorId)
                .executeUpdate();
        return revisionId;
    }

    /**
     * A revision freezes the line, the unit it was ordered in included (V20261020000400, SCRUM-473): an amendment is
     * matched against both, so a revision without them would refuse the next amendment of a line ordered in a carton.
     */
    private void insertLineRevision(UUID revisionId, PoLine line) {
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_line_revisions
                               (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty, unit_price,
                                tax_rate, discount_rate, line_subtotal, line_discount_amount, line_net, line_tax,
                                line_total, note, uom_conversion_id, unit_qty)
                        SELECT :id, :line, :revision, :item, :uom, :qty, :price, :taxRate, 0, :subtotal, 0,
                               :subtotal, :tax, :total, :note, CAST(:unit AS uuid), CAST(:unitQty AS integer)""")
                .setParameter("unit", line.packingUnit() == null ? null : line.packingUnit().uomConversionId())
                .setParameter("unitQty", line.packingUnit() == null ? null : line.packingUnit().count())
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

    // ------------------------------------------------------------------ amendments (SCRUM-117)

    /**
     * Native SQL, like {@link #recordRevision}: revisions and their lines are append-only rows written once, never
     * read back through JPA.
     */
    @Override
    public UUID recordAmendment(PurchaseOrder order, long revisionNo, UUID actorId,
                                PurchaseOrder.ProposedAmendment proposal) {
        if (revisionNo < 1) {
            throw new IllegalArgumentException("Revision 0 is the first submission, never an amendment");
        }
        UUID revisionId = insertRevision(order, revisionNo, actorId, truncate(proposal.reason(), 500),
                proposal.expectedAt(), proposal.note(), proposal.subtotal(), proposal.taxTotal(),
                proposal.totalAmount(),
                "{\"previousTotalAmount\":" + order.totalAmount().amount().toPlainString() + "}");
        proposal.kept().forEach(line -> insertLineRevision(revisionId, line.after()));
        return revisionId;
    }

    @Override
    public Optional<PendingAmendment> pendingAmendment(PurchaseOrderId id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT r.id, r.revision_no, r.changed_by, r.changed_at, r.change_summary,
                               (SELECT COALESCE(SUM(lr.line_total), 0) FROM procurement.purchase_order_line_revisions lr
                                 WHERE lr.po_revision_id = r.id)
                          FROM procurement.purchase_orders p
                          JOIN procurement.purchase_order_revisions r ON r.id = p.pending_revision_id
                         WHERE p.id = :po AND r.kind = 'AMENDMENT' AND p.status <> 'PENDING_APPROVAL'""")
                .setParameter("po", id.value())
                .getResultList();
        return rows.stream().findFirst().map(r -> new PendingAmendment((UUID) r[0], ((Number) r[1]).longValue(),
                (UUID) r[2], NativeValues.instant(r[3]), (String) r[4], (BigDecimal) r[5]));
    }

    /**
     * SQL rather than the entities: a line's content is not editable through {@link PoLineJpaEntity} once the order
     * left DRAFT, its {@code po_revision_id} is not mapped at all, and the line guard accepts the new content only in
     * the UPDATE that moves {@code po_revision_id} to the revision that froze it - one statement per concern, over
     * every line at once. The pending pointer is checked in the header statement, which runs last: a revision that is
     * not the one waiting fails it, and the exception rolls the line statements back with the transaction.
     */
    @Override
    public void applyAmendment(PurchaseOrderId id, UUID revisionId, long revisionNo) {
        entityManager.flush();
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_order_lines l
                           SET ordered_qty = lr.ordered_qty, unit_qty = lr.unit_qty, unit_price = lr.unit_price,
                               tax_rate = lr.tax_rate, note = lr.note, line_subtotal = lr.line_subtotal,
                               line_net = lr.line_net, line_tax = lr.line_tax, line_total = lr.line_total,
                               po_revision_id = lr.po_revision_id, version = l.version + 1, last_modified_at = NOW()
                          FROM procurement.purchase_order_line_revisions lr
                         WHERE lr.po_revision_id = :revision AND lr.po_line_id = l.id AND l.po_id = :po""")
                .setParameter("revision", revisionId)
                .setParameter("po", id.value())
                .executeUpdate();
        // A removed line stays, CANCELLED: earlier revisions and the receipts' history still name it.
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_order_lines l
                           SET status = 'CANCELLED', version = l.version + 1, last_modified_at = NOW()
                         WHERE l.po_id = :po AND l.status NOT IN ('CANCELLED', 'WITHDRAWN')
                           AND NOT EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions lr
                                            WHERE lr.po_revision_id = :revision AND lr.po_line_id = l.id)""")
                .setParameter("revision", revisionId)
                .setParameter("po", id.value())
                .executeUpdate();
        int header = entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_orders p
                           SET expected_date = CAST(r.snapshot_header ->> 'expectedDate' AS date),
                               note = r.snapshot_header ->> 'note',
                               subtotal = t.subtotal, tax_total = t.tax,
                               total_amount = t.subtotal - p.discount_total + t.tax + p.shipping_fee,
                               version = p.version + 1, last_modified_at = NOW()
                          FROM procurement.purchase_order_revisions r,
                               (SELECT COALESCE(SUM(line_subtotal), 0) AS subtotal, COALESCE(SUM(line_tax), 0) AS tax
                                  FROM procurement.purchase_order_lines
                                 WHERE po_id = :po AND status NOT IN ('CANCELLED', 'WITHDRAWN')) t
                         WHERE p.id = :po AND p.pending_revision_id = :revision AND r.id = :revision
                           AND r.po_id = p.id AND r.kind = 'AMENDMENT' AND r.revision_no = :no""")
                .setParameter("revision", revisionId)
                .setParameter("no", revisionNo)
                .setParameter("po", id.value())
                .executeUpdate();
        if (header != 1) {
            throw new IllegalStateException("Revision " + revisionId + " (" + revisionNo
                    + ") is not the amendment waiting on purchase order " + id.value());
        }
        refresh(id);
    }

    @Override
    public void dropAmendment(PurchaseOrderId id) {
        entityManager.flush();
        entityManager.createNativeQuery("""
                        UPDATE procurement.purchase_orders SET pending_revision_id = NULL, version = version + 1,
                               last_modified_at = NOW() WHERE id = :po""")
                .setParameter("po", id.value())
                .executeUpdate();
        refresh(id);
    }

    /** The SKU is the inventory item's, read through {@code inventory :: api} in one call, as {@link #toDomain} does. */
    @Override
    public List<RevisionLine> revisionLines(UUID revisionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT lr.po_line_id, l.line_no, lr.inventory_item_id, lr.ordered_qty, lr.unit_price,
                               lr.tax_rate, lr.note
                          FROM procurement.purchase_order_line_revisions lr
                          JOIN procurement.purchase_order_lines l ON l.id = lr.po_line_id
                         WHERE lr.po_revision_id = :revision
                         ORDER BY l.line_no""")
                .setParameter("revision", revisionId)
                .getResultList();
        Map<UUID, String> skus = inventory.skusOf(rows.stream().map(r -> (UUID) r[2]).collect(Collectors.toSet()));
        return rows.stream().map(r -> new RevisionLine((UUID) r[0], ((Number) r[1]).intValue(),
                skus.getOrDefault((UUID) r[2], "UNKNOWN"), ((BigDecimal) r[3]).intValueExact(), (BigDecimal) r[4],
                (BigDecimal) r[5], (String) r[6])).toList();
    }

    /** The order and its lines as SQL left them, for whoever reads them through JPA later in the transaction. */
    private void refresh(PurchaseOrderId id) {
        jpa.findById(id.value()).ifPresent(order -> {
            entityManager.refresh(order);
            order.getLines().forEach(entityManager::refresh);
        });
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
    public void recordAutoApproval(UUID revisionId, UUID approverId, UUID limitHolderId, ApprovalLimit limit,
                                   Instant at, String reason) {
        entityManager.createNativeQuery("""
                        INSERT INTO procurement.purchase_order_approvals
                               (id, po_revision_id, step_no, approver_id, decision, decision_at, reason,
                                limit_id, limit_amount, limit_role_id, limit_holder_id, created_at, created_by)
                        VALUES (:id, :revision,
                                (SELECT COALESCE(MAX(step_no), 0) + 1 FROM procurement.purchase_order_approvals
                                  WHERE po_revision_id = :revision),
                                :approver, 'AUTO_APPROVED', :at, :reason,
                                :limitId, :limitAmount, :limitRole, :holder, NOW(), 'procurement')""")
                .setParameter("id", Identifiers.newId())
                .setParameter("revision", revisionId)
                .setParameter("approver", approverId)
                .setParameter("at", at)
                .setParameter("reason", reason == null ? null : truncate(reason, 500))
                .setParameter("limitId", limit.id())
                .setParameter("limitAmount", limit.amount().amount())
                .setParameter("limitRole", limit.roleId())
                .setParameter("holder", limitHolderId)
                .executeUpdate();
    }

    @Override
    public boolean hasBeenRejected(PurchaseOrderId id) {
        return (Boolean) entityManager.createNativeQuery("""
                        SELECT EXISTS (SELECT 1 FROM procurement.purchase_order_approvals a
                                         JOIN procurement.purchase_order_revisions r ON r.id = a.po_revision_id
                                        WHERE r.po_id = :po AND a.decision = 'REJECTED')""")
                .setParameter("po", id.value())
                .getSingleResult();
    }

    @Override
    public Optional<UUID> creatorOf(PurchaseOrderId id) {
        @SuppressWarnings("unchecked")
        List<UUID> creators = entityManager.createNativeQuery("""
                        SELECT actor_id FROM procurement.purchase_order_events
                         WHERE po_id = :po AND action = 'CREATED' AND actor_id IS NOT NULL
                         ORDER BY created_at, id LIMIT 1""")
                .setParameter("po", id.value())
                .getResultList();
        return creators.stream().findFirst();
    }

    @Override
    public Money autoApprovedTotal(UUID limitHolderId, UUID supplierId, Currency currency, Instant since,
                                   PurchaseOrderId except) {
        // One submission at a time per holder and supplier: the sum is read and then acted on in the same transaction.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "po-auto-approval:" + limitHolderId + ":" + supplierId)
                .getSingleResult();
        BigDecimal sum = (BigDecimal) entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(p.total_amount), 0) FROM procurement.purchase_orders p
                         WHERE p.supplier_id = :supplier AND p.currency = :currency AND p.id <> :except
                           AND p.status <> 'CANCELLED'
                           AND EXISTS (SELECT 1 FROM procurement.purchase_order_approvals a
                                         JOIN procurement.purchase_order_revisions r ON r.id = a.po_revision_id
                                        WHERE r.po_id = p.id AND a.decision = 'AUTO_APPROVED'
                                          AND a.limit_holder_id = :holder AND a.decision_at > :since)""")
                .setParameter("supplier", supplierId)
                .setParameter("currency", currency.getCurrencyCode())
                .setParameter("except", except.value())
                .setParameter("holder", limitHolderId)
                .setParameter("since", since)
                .getSingleResult();
        return new Money(sum, currency);
    }

    @Override
    public void recordEvent(PurchaseOrderId id, UUID revisionId, String action, UUID actorId, PurchaseOrderStatus from,
                            PurchaseOrderStatus to, String reason) {
        recordEvent(id, revisionId, action, actorId, from, to, reason, null);
    }

    @Override
    public void recordEvent(PurchaseOrderId id, UUID revisionId, String action, UUID actorId, PurchaseOrderStatus from,
                            PurchaseOrderStatus to, String reason, Map<String, String> payload) {
        events.record(id.value(), revisionId, action, actorId, from == null ? null : from.name(),
                to == null ? null : to.name(), reason, payload, "procurement");
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
            var policies = inventory.itemPolicies(order.lines().stream().map(PoLine::inventoryItemId).toList());
            var lines = order.lines().stream().map(l -> {
                var policy = policies.get(l.inventoryItemId());
                PackingUnit unit = l.packingUnit();
                return new POLineSummary(l.id(), l.lineNo(), l.inventoryItemId(), l.sku().code(), l.description(),
                        l.uom(), l.quantityOrdered(), l.quantityReceived(), l.unitPrice().amount(), l.taxRate(),
                        l.total(), l.status().name(), policy != null && policy.lotTracked(),
                        policy != null && policy.expiryTracked(), policy != null && policy.qcRequired(),
                        unit == null ? null : unit.unitOfMeasure(), unit == null ? null : unit.factor(),
                        unit == null ? null : unit.count());
            }).toList();
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
                e.getSubmittedBy(), e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(), e.isAutoApproved(),
                e.getConfirmedBy(), e.getConfirmedAt(), e.getClosedAt(), e.getCloseKind() == null ? null : e.getCloseKind().name(),
                e.getCloseReason(), e.getCancelReason(), e.getPaymentTermDays(), e.getLeadTimeDays(),
                e.getConfirmedAt(), e.getSupplierConfirmationStatus().name(), e.getSupplierRespondedAt(),
                e.getSupplierReference(), e.getSupplierResponseNote(), e.getVersion(),
                supplier == null || supplier.getOverReceiptTolerancePercent() == null ? BigDecimal.ZERO
                        : supplier.getOverReceiptTolerancePercent());
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

    /** The line's packing unit (SCRUM-473), null for a line in the base unit. */
    private static PackingUnit packingUnit(UomConversion unit, Integer count) {
        return unit == null ? null : new PackingUnit(unit.id(), unit.unitOfMeasure(), unit.factor(), count);
    }

    private PurchaseOrder toDomain(PurchaseOrderJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        Map<UUID, String> skus = inventory.skusOf(e.getLines().stream().map(PoLineJpaEntity::getInventoryItemId)
                .collect(Collectors.toSet()));
        Map<UUID, Integer> received = receivedByLine(e.getId());
        List<PoLineJpaEntity> live = e.getLines().stream().filter(l -> l.getStatus() != PoLineStatus.WITHDRAWN).toList();
        Map<UUID, UomConversion> units = lineUnits.units(live, skus);
        List<PoLine> lines = live.stream()
                .map(l -> new PoLine(l.getId(), l.getLineNo(), l.getInventoryItemId(),
                new Sku(skus.getOrDefault(l.getInventoryItemId(), "UNKNOWN")), l.getUom(), l.getDescription(),
                l.getOrderedQuantity().intValueExact(), received.getOrDefault(l.getId(), 0),
                new Money(l.getUnitPrice(), currency), l.getTaxRate(), l.getStatus(),
                packingUnit(units.get(l.getId()), l.getUnitQuantity()))).toList();
        return new PurchaseOrder(new PurchaseOrder.State(new PurchaseOrderId(e.getId()), e.getPoNumber(), e.getType(),
                e.getProductionOrderId(), e.getSupplierId(), e.getWarehouseId(), e.getStatus(), currency, lines,
                e.getOrderDate(), e.getExpectedAt(), e.getNote(), e.getPaymentTermDays(), e.getLeadTimeDays(),
                e.getRevisionNo(), e.getActiveRevisionId(), e.getPendingRevisionId(), e.getSubmittedBy(),
                e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(), e.isAutoApproved(), e.getConfirmedBy(),
                e.getConfirmedAt(), e.getClosedBy(), e.getClosedAt(), e.getCloseKind(), e.getCloseReason(), e.getCancelReason(),
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

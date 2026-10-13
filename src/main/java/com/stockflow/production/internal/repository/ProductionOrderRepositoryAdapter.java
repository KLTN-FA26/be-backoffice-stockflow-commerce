package com.stockflow.production.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.entity.ProductionOrderJpaEntity;
import com.stockflow.production.internal.entity.ProductionOrderStatusHistoryJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
class ProductionOrderRepositoryAdapter implements ProductionOrderRepository, ProductionOrderSearch {

    private final ProductionOrderJpaRepository jpa;
    private final EntityManager entityManager;

    ProductionOrderRepositoryAdapter(ProductionOrderJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ProductionOrder> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<ProductionOrder> findByIdForUpdate(UUID id) {
        if (entityManager.createNativeQuery("SELECT id FROM production.production_order WHERE id = :id FOR UPDATE")
                .setParameter("id", id).getResultList().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findById(id).map(entity -> {
            entityManager.refresh(entity);
            return toDomain(entity);
        });
    }

    @Override
    public void save(ProductionOrder order) {
        ProductionOrderJpaEntity entity = jpa.findById(order.id())
                .orElseThrow(() -> new IllegalStateException("No production order " + order.id()));
        entity.applyWorkflow(order);
        jpa.saveAndFlush(entity);
    }

    @Override
    public void recordTransition(UUID productionOrderId, ProductionOrder.Transition transition, Instant at, UUID by) {
        entityManager.persist(new ProductionOrderStatusHistoryJpaEntity(Identifiers.newId(), productionOrderId,
                transition.from() == null ? null : transition.from().name(), transition.to().name(),
                transition.reason(), at, by));
        entityManager.flush();
    }

    @Override
    public Optional<ProductionOrder> findBySource(ProductionOrder.Type type, UUID sourceId) {
        return findEntity(type, sourceId).map(this::toDomain);
    }

    /**
     * BR-PRD-01 under concurrency. A transaction-scoped advisory lock on the source serialises two
     * creators of the same source: the second waits until the first commits or rolls back, then its
     * lookup — a new statement, so a new READ COMMITTED snapshot — sees the winner and returns it. A
     * plain find-then-insert would let both insert and the loser would fail on the unique index with
     * its transaction aborted; catching that error is not possible in PostgreSQL without a savepoint.
     * The unique indexes ({@code uk_production_order_line}, {@code uk_production_order_sample_request})
     * stay the backstop for any writer that does not come through here.
     */
    @Override
    public ProductionOrder createIfAbsent(ProductionOrder candidate, UUID createdBy, Instant at) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "production-source:" + candidate.sourceId())
                .getSingleResult();
        Optional<ProductionOrderJpaEntity> existing = findEntity(candidate.type(), candidate.sourceId());
        if (existing.isPresent()) {
            return toDomain(existing.get());
        }
        ProductionOrderJpaEntity entity = new ProductionOrderJpaEntity(candidate);
        entityManager.persist(entity);
        entityManager.persist(new ProductionOrderStatusHistoryJpaEntity(Identifiers.newId(), candidate.id(), null,
                candidate.status().name(), creationReason(candidate), at, createdBy));
        entityManager.flush();
        // Read back the columns the table defaults (mode, zero quantities) so a reader in this
        // transaction sees the row as stored.
        entityManager.refresh(entity);
        return toDomain(entity);
    }

    private static String creationReason(ProductionOrder order) {
        return order.type() == ProductionOrder.Type.SAMPLE ? "Sample request submitted" : "Order line released";
    }

    @Override
    public void createChild(ProductionOrder child, UUID createdBy, Instant at) {
        ProductionOrderJpaEntity entity = new ProductionOrderJpaEntity(child);
        entity.applyWorkflow(child);   // the inherited prepress result: READY needs it (ck_production_order_prepress)
        entityManager.persist(entity);
        entityManager.persist(new ProductionOrderStatusHistoryJpaEntity(Identifiers.newId(), child.id(), null,
                child.status().name(), switch (child.origin()) {
                    case SPLIT -> "Split off for subcontracting";
                    case SHORTFALL -> "A subcontractor's shortfall, printed in house";
                    case REPLACEMENT -> "Replaces a cancelled subcontracted order, printed in house";
                }, at, createdBy));
        entityManager.flush();
    }

    @Override
    public Optional<UUID> idOfPurchaseOrder(UUID purchaseOrderId) {
        return jpa.findIdByPurchaseOrder(purchaseOrderId);
    }

    /**
     * The line's units over the whole tree, less the root's own plan. Every order counts its plan - a SHORTFALL took
     * its units off the order it came from - except one cancelled and REPLACED in house, the root included, whose
     * replacement carries the same units. A cancelled order keeps its plan, so a replaced root is left out of the sum
     * yet still taken off: the result is then its children's units, and its plan plus them is still the line's.
     * The subtraction sits in the select list: inside the WHERE it would bind to the NOT and fail on PostgreSQL.
     */
    @Override
    public int splitOffOf(UUID parentId) {
        Number total = (Number) entityManager.createNativeQuery("""
                        WITH RECURSIVE tree AS (
                            SELECT id, planned_quantity, status FROM production.production_order WHERE id = :id
                            UNION ALL
                            SELECT c.id, c.planned_quantity, c.status FROM production.production_order c
                              JOIN tree t ON c.parent_id = t.id)
                        SELECT (SELECT COALESCE(SUM(planned_quantity), 0) FROM tree t
                                 WHERE NOT (t.status = 'CANCELLED' AND EXISTS (
                                       SELECT 1 FROM production.production_order r
                                        WHERE r.parent_id = t.id AND r.origin = 'REPLACEMENT')))
                             - (SELECT planned_quantity FROM production.production_order WHERE id = :id)""")
                .setParameter("id", parentId).getSingleResult();
        return total.intValue();
    }

    @Override
    public List<UUID> idsOfSalesOrder(UUID salesOrderId) {
        return jpa.findIdsBySalesOrder(salesOrderId);
    }

    @Override
    public boolean completedForAnotherOrder(UUID designSnapshotId, UUID excludingSalesOrderId) {
        return jpa.existsCompletedForAnotherOrder(designSnapshotId, excludingSalesOrderId);
    }

    /** Same counter table and upsert as TO-/ADJ-/GR- numbers; a rollback leaves a gap, never a duplicate. */
    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('LSX', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "LSX-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<ProductionOrderJpaEntity> spec = Specification
                .<ProductionOrderJpaEntity>where(Specs.eq("warehouseId", criteria.warehouseId()))
                .and(Specs.eq("sampleRequestId", criteria.sampleRequestId()))
                .and(Specs.eq("salesOrderId", criteria.salesOrderId()))
                .and(Specs.contains("orderNumber", criteria.number()))
                .and(criteria.types() == null || criteria.types().isEmpty() ? null : Specs.in("type", criteria.types()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : Specs.in("status", criteria.statuses()))
                .and(criteria.dueFrom() == null ? null
                        : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dueDate"), criteria.dueFrom()))
                .and(criteria.dueTo() == null ? null
                        : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("dueDate"), criteria.dueTo()))
                .and(WarehouseScope.warehousesIn("warehouseId"));
        return jpa.findAll(spec, pageable).map(ProductionOrderRepositoryAdapter::toRow);
    }

    @Override
    public Optional<Detail> detail(UUID id) {
        return jpa.findById(id).map(e -> new Detail(toRow(e), e.getApprovedSampleId(), e.getDesignChecksum(),
                e.getIssuedQuantity(), e.getPrintedQuantity(), e.getScrapQuantity(), e.getPrepressCheckedAt(),
                e.getCompletedAt(), e.getCancelledAt(), e.getCancellationReason(), e.getCreatedBy(),
                e.getLastModifiedAt(), e.getVersion(), history(id), e.getOrigin(),
                e.getSubcontractMethod() == null ? null : new Subcontract(e.getSubcontractMethod(),
                        e.getSubcontractorId(), e.getSubcontractUnitPrice(), e.getSubcontractCurrency(),
                        e.getPurchaseOrderId())));
    }

    private List<HistoryEntry> history(UUID productionOrderId) {
        return entityManager.createQuery("""
                        select h from ProductionOrderStatusHistoryJpaEntity h
                         where h.productionOrderId = :id order by h.changedAt, h.id""",
                        ProductionOrderStatusHistoryJpaEntity.class)
                .setParameter("id", productionOrderId)
                .getResultStream()
                .map(h -> new HistoryEntry(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getChangedAt(),
                        h.getChangedBy()))
                .toList();
    }

    @Override
    public Map<UUID, Row> forSamples(Collection<UUID> sampleRequestIds) {
        if (sampleRequestIds.isEmpty()) {
            return Map.of();
        }
        return jpa.findByTypeAndSampleRequestIdIn(ProductionOrder.Type.SAMPLE, sampleRequestIds).stream()
                .map(ProductionOrderRepositoryAdapter::toRow)
                .collect(Collectors.toMap(Row::sampleRequestId, Function.identity()));
    }

    private Optional<ProductionOrderJpaEntity> findEntity(ProductionOrder.Type type, UUID sourceId) {
        return type == ProductionOrder.Type.SAMPLE ? jpa.findForSample(sourceId) : jpa.findRootForLine(sourceId);
    }

    private ProductionOrder toDomain(ProductionOrderJpaEntity e) {
        return ProductionOrder.restore(e.getId(), e.getOrderNumber(), e.getType(), e.getStatus(),
                e.getSampleRequestId(), e.getSalesOrderId(), e.getSalesOrderLineId(), e.getApprovedSampleId(),
                new ProductionOrder.Work(e.getWarehouseId(), new Sku(e.getBlankSku()), e.getDesignSnapshotId(),
                        e.getDesignChecksum(), e.getPlannedQuantity(), e.getDueDate()))
                .withWorkflow(e.getStatusBeforeHold(), e.getHoldReason(), e.getHoldNote(), e.getPrepressCheckedBy(),
                        e.getPrepressCheckedAt())
                .withSubcontract(ProductionOrder.Mode.valueOf(e.getMode()), e.getParentId(),
                        e.getOrigin() == null ? null : ProductionOrder.Origin.valueOf(e.getOrigin()),
                        e.getSubcontractMethod() == null ? null : new ProductionOrder.Subcontract(
                                ProductionOrder.SubcontractMethod.valueOf(e.getSubcontractMethod()),
                                e.getSubcontractorId(), e.getSubcontractUnitPrice(), e.getSubcontractCurrency(),
                                e.getPurchaseOrderId()))
                .withProgress(e.getIssuedQuantity(), e.getPrintedQuantity(), e.getGoodQuantity(), e.getScrapQuantity(),
                        passedOf(e.getId()), e.getCompletedAt(), e.getCancelledAt(), e.getCancellationReason());
    }

    /** Good units through QC so far: the sum of the QC rounds, which are the record of it. */
    private int passedOf(UUID productionOrderId) {
        Number passed = (Number) entityManager.createNativeQuery(
                        "SELECT COALESCE(SUM(passed_quantity), 0) FROM production.production_qc_result "
                                + "WHERE production_order_id = :id")
                .setParameter("id", productionOrderId)
                .getSingleResult();
        return passed.intValue();
    }

    private static Row toRow(ProductionOrderJpaEntity e) {
        return new Row(e.getId(), e.getOrderNumber(), e.getType(), e.getMode(), e.getStatus(),
                e.getHoldReason() == null ? null : e.getHoldReason().name(),
                e.getWarehouseId(), e.getBlankSku(), e.getDesignSnapshotId(), e.getPlannedQuantity(),
                e.getGoodQuantity(), e.getDueDate(), e.getSampleRequestId(), e.getSalesOrderId(),
                e.getSalesOrderLineId(), e.getParentId(), e.getCreatedAt());
    }
}

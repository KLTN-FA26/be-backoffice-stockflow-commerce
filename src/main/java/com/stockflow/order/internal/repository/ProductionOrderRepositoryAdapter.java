package com.stockflow.order.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderSaved;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.entity.ProductionOrderJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link ProductionOrderRepository} and {@link ProductionOrderSearch} over {@code ordering.production_order}, its lines
 * and its history. A lock refreshes what it locked: a production order already in the persistence context would
 * otherwise come back as read before the lock (CLAUDE.md §4).
 *
 * <p>Every save publishes a {@link ProductionOrderSaved} (4A-7): here, where every path writing a production order
 * passes, so its sales order's delay alerts are recomputed whichever operation changed it.</p>
 */
@Repository
class ProductionOrderRepositoryAdapter implements ProductionOrderRepository, ProductionOrderSearch {

    private final ProductionOrderJpaRepository jpa;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    ProductionOrderRepositoryAdapter(ProductionOrderJpaRepository jpa, EntityManager entityManager,
                                     ApplicationEventPublisher events) {
        this.jpa = jpa;
        this.entityManager = entityManager;
        this.events = events;
    }

    @Override
    public Optional<ProductionOrder> findById(UUID id) {
        return jpa.findById(id).map(entity -> toDomain(entity, linesOf(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public Optional<ProductionOrder> findByIdForUpdate(UUID id) {
        ProductionOrderJpaEntity entity = entityManager.find(ProductionOrderJpaEntity.class, id);
        if (entity == null) {
            return Optional.empty();
        }
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(toDomain(entity, linesOf(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public List<ProductionOrder> ofSalesOrder(UUID salesOrderId) {
        List<ProductionOrderJpaEntity> entities = jpa.findBySalesOrderIdOrderByCreatedAtAscIdAsc(salesOrderId);
        Map<UUID, List<ProductionOrder.Line>> lines = linesOf(entities.stream().map(ProductionOrderJpaEntity::getId)
                .toList());
        return entities.stream().map(e -> toDomain(e, lines.getOrDefault(e.getId(), List.of()))).toList();
    }

    @Override
    public String nextCode(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('PRD', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "PRD-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    @Override
    public Set<String> producedBefore(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        List<String> found = entityManager.createNativeQuery("""
                        SELECT DISTINCT l.sku FROM ordering.production_order_line l
                          JOIN ordering.production_order p ON p.id = l.production_order_id
                         WHERE l.sku IN (:skus) AND p.production_started_at IS NOT NULL""")
                .setParameter("skus", skus)
                .getResultList();
        return new HashSet<>(found);
    }

    @Override
    public ProductionOrder save(ProductionOrder order) {
        ProductionOrderJpaEntity entity = jpa.findById(order.id()).orElse(null);
        boolean created = entity == null;
        if (created) {
            entity = new ProductionOrderJpaEntity(order.id(), order.code(), order.salesOrderId(),
                    order.deliveryBatchId(), order.customerId(), order.sampleRequiredByDefault(), order.currency(),
                    order.drawnUpBy());
        } else if (entity.getVersion() != order.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Production order %s changed since it was read".formatted(order.code()));
        }
        entity.apply(order.producerId(), order.receivingWarehouseId(), order.needByDate(), order.status(),
                order.sampleApprovalRequired(), order.sampleWaiverReason(), order.unitPrice(), order.note(),
                order.submittedAt(), order.approvedBy(), order.approvedAt(), order.returnedReason(), order.sentAt(),
                order.productionStartedAt(), order.cancelledBy(), order.cancelledAt(), order.cancellationReason(),
                order.closedBy(), order.closedAt(), order.closingReason());
        long readAt = entity.getVersion();
        ProductionOrderJpaEntity saved = jpa.saveAndFlush(entity);
        if (!created && saved.getVersion() == readAt) {
            // Only the lines changed (an edit of cartons or packing, or a receipt): they are written by native SQL
            // below, which Hibernate never sees, so the header row would keep its version and an edit made at the
            // old one would silently overwrite this one. The increment is immediate, so the version returned is
            // the one a client edits at next.
            entityManager.lock(saved, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        writeLines(order, created);
        for (ProductionOrder.Change change : order.pullChanges()) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status,
                                                                                  to_status, reason, changed_by,
                                                                                  changed_at)
                            VALUES (:id, :order, :from, :to, :reason, :by, :at)""")
                    .setParameter("id", Identifiers.newId())
                    .setParameter("order", order.id())
                    .setParameter("from", change.from() == null ? null : change.from().name())
                    .setParameter("to", change.to().name())
                    .setParameter("reason", change.reason())
                    .setParameter("by", change.by())
                    .setParameter("at", change.at())
                    .executeUpdate();
        }
        events.publishEvent(new ProductionOrderSaved(order.id(), order.salesOrderId()));
        return toDomain(saved, linesOf(List.of(order.id())).getOrDefault(order.id(), List.of()));
    }

    /**
     * Lines are written with the draft and rewritten while it is one; afterwards only what was received moves (4A-5),
     * which the database lets through ({@code check_production_order_line}).
     */
    private void writeLines(ProductionOrder order, boolean created) {
        if (!created && order.status() != ProductionOrderStatus.DRAFT) {
            for (ProductionOrder.Line line : order.lines()) {
                entityManager.createNativeQuery("""
                                UPDATE ordering.production_order_line SET received_cartons = :received
                                 WHERE id = :id AND received_cartons <> :received""")
                        .setParameter("received", line.receivedCartons())
                        .setParameter("id", line.id())
                        .executeUpdate();
            }
            return;
        }
        if (!created) {
            entityManager.createNativeQuery("DELETE FROM ordering.production_order_line WHERE production_order_id = :id")
                    .setParameter("id", order.id()).executeUpdate();
        }
        for (ProductionOrder.Line line : order.lines()) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku,
                                                                        cartons, units_per_carton, design_snapshot_id,
                                                                        print_file_checksum, packing_requirements,
                                                                        received_cartons)
                            VALUES (:id, :order, :line, :sku, :cartons, :per, :snapshot, :checksum, :packing,
                                    :received)""")
                    .setParameter("id", line.id())
                    .setParameter("order", order.id())
                    .setParameter("line", line.orderLineId())
                    .setParameter("sku", line.sku())
                    .setParameter("cartons", line.cartons())
                    .setParameter("per", line.unitsPerCarton())
                    .setParameter("snapshot", line.designSnapshotId())
                    .setParameter("checksum", line.printFileChecksum())
                    .setParameter("packing", line.packingRequirements())
                    .setParameter("received", line.receivedCartons())
                    .executeUpdate();
        }
    }

    @Override
    public List<HistoryEntry> history(UUID productionOrderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT from_status, to_status, reason, changed_by, changed_at
                          FROM ordering.production_order_status_history
                         WHERE production_order_id = :id ORDER BY changed_at, id""")
                .setParameter("id", productionOrderId)
                .getResultList();
        return rows.stream().map(row -> new HistoryEntry(
                row[0] == null ? null : ProductionOrderStatus.valueOf((String) row[0]),
                ProductionOrderStatus.valueOf((String) row[1]), (String) row[2], (UUID) row[3], instant(row[4])))
                .toList();
    }

    @Override
    public Page<ProductionOrder> search(UUID receivingWarehouseId, ProductionOrderStatus status, UUID producerId,
                                        UUID salesOrderId, Collection<UUID> warehouses, Pageable pageable) {
        Specification<ProductionOrderJpaEntity> spec = Specification
                .<ProductionOrderJpaEntity>where(Specs.eq("receivingWarehouseId", receivingWarehouseId))
                .and(Specs.eq("status", status))
                .and(Specs.eq("producerId", producerId))
                .and(Specs.eq("salesOrderId", salesOrderId))
                .and(warehouses == null ? null : warehouses.isEmpty()
                        ? (root, query, cb) -> cb.disjunction()
                        : (root, query, cb) -> root.get("receivingWarehouseId").in(warehouses));
        Page<ProductionOrderJpaEntity> page = jpa.findAll(spec, pageable);
        Map<UUID, List<ProductionOrder.Line>> lines = linesOf(page.map(ProductionOrderJpaEntity::getId).getContent());
        return page.map(e -> toDomain(e, lines.getOrDefault(e.getId(), List.of())));
    }

    private Map<UUID, List<ProductionOrder.Line>> linesOf(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                               design_snapshot_id, print_file_checksum, packing_requirements, received_cartons
                          FROM ordering.production_order_line WHERE production_order_id IN (:ids) ORDER BY sku, id""")
                .setParameter("ids", ids)
                .getResultList();
        return rows.stream().collect(Collectors.groupingBy(row -> (UUID) row[1], Collectors.mapping(
                row -> new ProductionOrder.Line((UUID) row[0], (UUID) row[2], (String) row[3],
                        ((Number) row[4]).intValue(), ((Number) row[5]).intValue(), (UUID) row[6], (String) row[7],
                        (String) row[8], ((Number) row[9]).intValue()), Collectors.toList())));
    }

    private static ProductionOrder toDomain(ProductionOrderJpaEntity e, List<ProductionOrder.Line> lines) {
        return new ProductionOrder(e.getId(), e.getCode(), e.getSalesOrderId(), e.getDeliveryBatchId(),
                e.getCustomerId(), e.getProducerId(), e.getReceivingWarehouseId(), e.getNeedByDate(), e.getStatus(),
                e.isSampleApprovalRequired(), e.isSampleRequiredByDefault(), e.getSampleWaiverReason(),
                e.getUnitPrice(), e.getCurrency(), e.getNote(), e.getDrawnUpBy(), e.getSubmittedAt(),
                e.getApprovedBy(), e.getApprovedAt(), e.getReturnedReason(), e.getSentAt(),
                e.getProductionStartedAt(), e.getCancelledBy(), e.getCancelledAt(), e.getCancellationReason(),
                e.getClosedBy(), e.getClosedAt(), e.getClosingReason(), lines, e.getVersion());
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> Instant.parse(value.toString());
        };
    }
}

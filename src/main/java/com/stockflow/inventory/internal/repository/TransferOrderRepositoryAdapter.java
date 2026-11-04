package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.persistence.Specs;
import com.stockflow.inventory.internal.domain.TransferLine;
import com.stockflow.inventory.internal.domain.TransferOrder;
import com.stockflow.inventory.internal.domain.TransferOrderRepository;
import com.stockflow.inventory.internal.domain.TransferStatus;
import com.stockflow.inventory.internal.entity.TransferOrderJpaEntity;
import com.stockflow.inventory.internal.entity.TransferOrderLineJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class TransferOrderRepositoryAdapter implements TransferOrderRepository, TransferOrderSearch {

    private final TransferOrderJpaRepository jpa;
    private final EntityManager entityManager;

    TransferOrderRepositoryAdapter(TransferOrderJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<TransferOrder> findById(UUID id) {
        return jpa.findByIdWithLines(id).map(TransferOrderRepositoryAdapter::toDomain);
    }

    @Override
    public List<TransferOrder> findAllById(java.util.Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : jpa.findAllWithLines(ids).stream()
                .map(TransferOrderRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<TransferOrder> findAwaitingDispatch(UUID warehouseId) {
        return jpa.findAwaitingDispatch(warehouseId).stream().map(TransferOrderRepositoryAdapter::toDomain).toList();
    }

    /** Same counter table and upsert as stock adjustment numbers; see StockAdjustmentRepositoryAdapter. */
    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('TO', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "TO-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    /**
     * Insert, or update the header and what was shipped. A DRAFT is the exception (SCRUM-462): its
     * header and lines are whatever the planner last saved, so changed lines are replaced — the old
     * rows deleted and flushed before the new ones go in, so a line number is never held twice
     * ({@code uk_transfer_order_line_no}). Nothing refers to a draft's lines yet.
     */
    @Override
    public TransferOrder save(TransferOrder order) {
        jpa.findByIdWithLines(order.id())
                .filter(stored -> stored.getStatus() == TransferStatus.DRAFT && order.status() == TransferStatus.DRAFT)
                .ifPresent(draft -> reviseDraft(draft, order));
        TransferOrderJpaEntity entity = jpa.findByIdWithLines(order.id()).orElseGet(() -> {
            TransferOrderJpaEntity created = new TransferOrderJpaEntity(order.id(), order.number(),
                    order.fromWarehouseId(), order.toWarehouseId(), order.reason(), order.expectedDate());
            order.lines().forEach(line -> created.addLine(new TransferOrderLineJpaEntity(
                    line.id(), line.lineNo(), line.sku().code(), line.lotNumber(), line.requested())));
            return created;
        });
        entity.apply(order.status(), order.submittedBy(), order.submittedAt(), order.approvedBy(),
                order.approvedAt(), order.dispatchedBy(), order.dispatchedAt(), order.cancelReason());
        entity.applyFulfillment(order.deliveryMode(), order.packedAt(), order.goodsLeftAt());
        entity.applyRejection(order.rejectedBy(), order.rejectedAt(), order.rejectionReason());
        for (TransferLine line : order.lines()) {
            entity.getLines().stream().filter(row -> row.getId().equals(line.id())).findFirst()
                    .ifPresent(row -> row.ship(line.shipped()));
        }
        return toDomain(jpa.saveAndFlush(entity));
    }

    private void reviseDraft(TransferOrderJpaEntity draft, TransferOrder order) {
        // The version moves even when only lines change: they are rows of their own (OPTIMISTIC_LOCK on a stale edit).
        entityManager.lock(draft, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        draft.reviseDraft(order.fromWarehouseId(), order.toWarehouseId(), order.reason(), order.expectedDate());
        List<UUID> stored = draft.getLines().stream().map(TransferOrderLineJpaEntity::getId).toList();
        List<UUID> wanted = order.lines().stream().map(TransferLine::id).toList();
        if (stored.equals(wanted)) {
            return;
        }
        draft.getLines().clear();
        entityManager.flush();
        order.lines().forEach(line -> draft.addLine(new TransferOrderLineJpaEntity(
                line.id(), line.lineNo(), line.sku().code(), line.lotNumber(), line.requested())));
    }

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<TransferOrderJpaEntity> spec = Specification
                .<TransferOrderJpaEntity>where(Specs.eq("fromWarehouseId", criteria.fromWarehouseId()))
                .and(Specs.eq("toWarehouseId", criteria.toWarehouseId()))
                .and(Specs.contains("transferNumber", criteria.number()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : Specs.in("status", criteria.statuses()))
                .and(com.stockflow.common.security.WarehouseScope.warehousesIn("fromWarehouseId", "toWarehouseId"));
        return jpa.findAll(spec, pageable).map(e -> new Row(e.getId(), e.getTransferNumber(), e.getFromWarehouseId(),
                e.getToWarehouseId(), e.getStatus(), e.getExpectedDate(), e.getCreatedAt(), e.getDispatchedAt(),
                e.getDeliveryMode(), e.getPackedAt()));
    }

    static TransferOrder toDomain(TransferOrderJpaEntity e) {
        return new TransferOrder(e.getId(), e.getTransferNumber(), e.getFromWarehouseId(), e.getToWarehouseId(),
                e.getReason(), e.getExpectedDate(), null,
                e.getLines().stream().map(l -> new TransferLine(l.getId(), l.getLineNo(), new Sku(l.getSku()),
                        l.getLotNumber(), l.getRequestedQty(), l.getShippedQty())).toList(),
                e.getStatus(), e.getSubmittedBy(), e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(),
                e.getDispatchedBy(), e.getDispatchedAt(), e.getCancelReason(), e.getVersion(), e.getCreatedAt())
                .restoreFulfillment(e.getDeliveryMode(), e.getPackedAt(), e.getGoodsLeftAt())
                .restoreRejection(e.getRejectedBy(), e.getRejectedAt(), e.getRejectionReason());
    }
}

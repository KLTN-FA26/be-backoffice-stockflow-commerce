package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.persistence.Specs;
import com.stockflow.inventory.internal.domain.TransferLine;
import com.stockflow.inventory.internal.domain.TransferOrder;
import com.stockflow.inventory.internal.domain.TransferOrderRepository;
import com.stockflow.inventory.internal.entity.TransferOrderJpaEntity;
import com.stockflow.inventory.internal.entity.TransferOrderLineJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    @Override
    public TransferOrder save(TransferOrder order) {
        TransferOrderJpaEntity entity = jpa.findByIdWithLines(order.id()).orElseGet(() -> {
            TransferOrderJpaEntity created = new TransferOrderJpaEntity(order.id(), order.number(),
                    order.fromWarehouseId(), order.toWarehouseId(), order.reason(), order.expectedDate());
            order.lines().forEach(line -> created.addLine(new TransferOrderLineJpaEntity(
                    line.id(), line.lineNo(), line.sku().code(), line.lotNumber(), line.requested())));
            return created;
        });
        entity.apply(order.status(), order.submittedBy(), order.submittedAt(), order.approvedBy(),
                order.approvedAt(), order.dispatchedBy(), order.dispatchedAt(), order.cancelReason());
        for (TransferLine line : order.lines()) {
            entity.getLines().stream().filter(row -> row.getId().equals(line.id())).findFirst()
                    .ifPresent(row -> row.ship(line.shipped()));
        }
        return toDomain(jpa.saveAndFlush(entity));
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
                e.getToWarehouseId(), e.getStatus(), e.getExpectedDate(), e.getCreatedAt(), e.getDispatchedAt()));
    }

    static TransferOrder toDomain(TransferOrderJpaEntity e) {
        return new TransferOrder(e.getId(), e.getTransferNumber(), e.getFromWarehouseId(), e.getToWarehouseId(),
                e.getReason(), e.getExpectedDate(), null,
                e.getLines().stream().map(l -> new TransferLine(l.getId(), l.getLineNo(), new Sku(l.getSku()),
                        l.getLotNumber(), l.getRequestedQty(), l.getShippedQty())).toList(),
                e.getStatus(), e.getSubmittedBy(), e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(),
                e.getDispatchedBy(), e.getDispatchedAt(), e.getCancelReason(), e.getVersion(), e.getCreatedAt());
    }
}

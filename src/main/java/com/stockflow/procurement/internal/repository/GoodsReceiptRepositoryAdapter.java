package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.procurement.internal.domain.GoodsReceipt;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.QcInspection;
import com.stockflow.procurement.internal.domain.ReceiptLine;
import com.stockflow.procurement.internal.entity.GoodsReceiptJpaEntity;
import com.stockflow.procurement.internal.entity.GoodsReceiptLineJpaEntity;
import com.stockflow.procurement.internal.entity.QcInspectionJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class GoodsReceiptRepositoryAdapter implements GoodsReceiptRepository, GoodsReceiptSearch {

    private final GoodsReceiptJpaRepository jpa;
    private final EntityManager entityManager;

    GoodsReceiptRepositoryAdapter(GoodsReceiptJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<GoodsReceipt> findById(UUID receiptId) {
        return jpa.findByIdWithLines(receiptId).map(GoodsReceiptRepositoryAdapter::toDomain);
    }

    /** Same counter table and upsert as stock adjustment and transfer numbers. */
    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('GR', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "GR-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    /**
     * Lines are synchronised by id. A draft whose lines were replaced removes the old rows and
     * flushes BEFORE inserting the new ones: Hibernate orders inserts ahead of deletes, and a new line
     * for the same PO line and lot would otherwise collide with the old one on
     * {@code uk_goods_receipt_lines_lot} — and be counted twice by the tolerance trigger.
     */
    @Override
    public GoodsReceipt save(GoodsReceipt receipt) {
        GoodsReceiptJpaEntity entity = jpa.findByIdWithLines(receipt.id()).orElseGet(() ->
                new GoodsReceiptJpaEntity(receipt.id(), receipt.number(), receipt.purchaseOrderId(),
                        receipt.purchaseOrderRevisionId(), receipt.warehouseId(), receipt.receivedAt(),
                        receipt.receivedBy(), receipt.deliveryNote(), receipt.note()));

        Set<UUID> wanted = receipt.lines().stream().map(ReceiptLine::id).collect(Collectors.toSet());
        if (entity.getLines().removeIf(row -> !wanted.contains(row.getId())) && !entity.isNew()) {
            jpa.saveAndFlush(entity);
        }
        Map<UUID, GoodsReceiptLineJpaEntity> rows = entity.getLines().stream()
                .collect(Collectors.toMap(GoodsReceiptLineJpaEntity::getId, row -> row));
        for (ReceiptLine line : receipt.lines()) {
            GoodsReceiptLineJpaEntity row = rows.get(line.id());
            if (row == null) {
                row = new GoodsReceiptLineJpaEntity(line.id(), line.poLineId(), line.inventoryItemId(),
                        line.locationId(), BigDecimal.valueOf(line.receivedQuantity()), line.lotNumber(),
                        line.expiryDate(), line.note(), line.qcRequired());
                entity.addLine(row);
            }
            if (line.movedToQc() && row.getMovedToQcAt() == null) {
                row.movedToQc(line.qcLocationId(), line.movedToQcAt(), line.movedToQcBy());
            }
            Set<UUID> recorded = row.getInspections().stream().map(QcInspectionJpaEntity::getId)
                    .collect(Collectors.toSet());
            for (QcInspection inspection : line.inspections()) {
                if (!recorded.contains(inspection.id())) {
                    row.addInspection(new QcInspectionJpaEntity(inspection.id(), inspection.outcome(),
                            BigDecimal.valueOf(inspection.quantity()), inspection.targetLocationId(),
                            inspection.reason(), inspection.inspectedBy(), inspection.inspectedAt()));
                }
            }
        }
        entity.apply(receipt.status(), receipt.confirmedAt(), receipt.confirmedBy(), receipt.closedAt());
        return toDomain(jpa.saveAndFlush(entity));
    }

    @Override
    public Map<UUID, Integer> receivedOnOtherReceipts(Iterable<UUID> poLineIds, UUID excludingReceiptId) {
        List<UUID> ids = new ArrayList<>();
        poLineIds.forEach(ids::add);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return toTotals(jpa.sumByPoLineExcluding(ids, excludingReceiptId, GoodsReceiptStatus.CANCELLED));
    }

    @Override
    public Map<UUID, Integer> confirmedQuantities(UUID purchaseOrderId) {
        return toTotals(jpa.sumByPoLineOfOrder(purchaseOrderId,
                List.of(GoodsReceiptStatus.DRAFT, GoodsReceiptStatus.CANCELLED)));
    }

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<GoodsReceiptJpaEntity> spec = Specification
                .<GoodsReceiptJpaEntity>where(Specs.eq("purchaseOrderId", criteria.purchaseOrderId()))
                .and(Specs.eq("warehouseId", criteria.warehouseId()))
                .and(Specs.contains("receiptNumber", criteria.number()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : Specs.in("status", criteria.statuses()))
                .and(receivedBetween(criteria.receivedFrom(), criteria.receivedTo()))
                .and(com.stockflow.common.security.WarehouseScope.warehousesIn("warehouseId"));
        return jpa.findAll(spec, pageable).map(e -> new Row(e.getId(), e.getReceiptNumber(), e.getPurchaseOrderId(),
                e.getWarehouseId(), e.getStatus(), e.getDeliveryNote(), e.getReceivedAt(), e.getReceivedBy(),
                e.getConfirmedAt(), e.getClosedAt()));
    }

    private static Specification<GoodsReceiptJpaEntity> receivedBetween(Instant from, Instant to) {
        if (from == null && to == null) {
            return null;
        }
        return (root, query, cb) -> {
            var path = root.<Instant>get("receivedAt");
            if (from != null && to != null) {
                return cb.and(cb.greaterThanOrEqualTo(path, from), cb.lessThan(path, to));
            }
            return from != null ? cb.greaterThanOrEqualTo(path, from) : cb.lessThan(path, to);
        };
    }

    private static Map<UUID, Integer> toTotals(List<Object[]> rows) {
        Map<UUID, Integer> totals = new HashMap<>();
        for (Object[] row : rows) {
            totals.put((UUID) row[0], ((BigDecimal) row[1]).intValueExact());
        }
        return totals;
    }

    static GoodsReceipt toDomain(GoodsReceiptJpaEntity e) {
        List<ReceiptLine> lines = e.getLines().stream().map(l -> new ReceiptLine(l.getId(), l.getPoLineId(),
                l.getInventoryItemId(), l.getLocationId(), l.getReceivedQty().intValueExact(), l.getLotNumber(),
                l.getExpiryDate(), l.getNote(), l.isQcRequired(), l.getQcLocationId(), l.getMovedToQcAt(),
                l.getMovedToQcBy(),
                l.getInspections().stream().map(i -> new QcInspection(i.getId(), i.getOutcome(),
                        i.getQuantity().intValueExact(), i.getTargetLocationId(), i.getReason(), i.getInspectedBy(),
                        i.getInspectedAt())).toList())).toList();
        return new GoodsReceipt(e.getId(), e.getReceiptNumber(), e.getPurchaseOrderId(),
                e.getPurchaseOrderRevisionId(), e.getWarehouseId(), e.getReceivedAt(), e.getReceivedBy(),
                e.getDeliveryNote(), e.getNote(), lines, e.getStatus(), e.getConfirmedAt(), e.getConfirmedBy(),
                e.getClosedAt(), e.getVersion());
    }
}

package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.procurement.internal.domain.GoodsReceipt;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.ReceiptLine;
import com.stockflow.procurement.internal.entity.GoodsReceiptJpaEntity;
import com.stockflow.procurement.internal.entity.GoodsReceiptLineJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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

    /**
     * The row is locked first with a native {@code FOR UPDATE} and the aggregate read afterwards, then refreshed: a
     * caller that queued behind another transaction must see what that transaction committed, and an entity already
     * in the persistence context would otherwise be returned as it was before the wait (the same reasoning as
     * {@code StockItemRepositoryAdapter.findByIdForUpdate}).
     */
    @Override
    public Optional<GoodsReceipt> findByIdForUpdate(UUID receiptId) {
        if (entityManager.createNativeQuery("select id from procurement.goods_receipts where id = :id for update")
                .setParameter("id", receiptId).getResultList().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findByIdWithLines(receiptId).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return toDomain(entity);
        });
    }

    /** Same counter table and upsert as stock adjustment numbers. */
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
     * Lines are synchronised by id. A line the draft recounted keeps its id (stock packages point at it) and its row is
     * updated in place. Removed rows are flushed before new ones are inserted: Hibernate orders inserts ahead of
     * deletes, and a new line for the production order line of a removed one would otherwise collide with it on
     * {@code uk_goods_receipt_lines_production_order_line}.
     *
     * <p>4A-5: BR-02 of a production receipt is a deferred constraint trigger
     * ({@code tg_goods_receipt_lines_production_cartons}, on the cartons of every receipt not cancelled). Setting it
     * IMMEDIATE once the receipt is flushed runs it here, and a refusal is a {@code DataIntegrityViolationException}
     * from this call (a 409) rather than a failed commit (a 500). The check that a production receipt is registered
     * with its production order ({@code tg_goods_receipts_production_registered}) stays deferred: the registration
     * follows the save in the same transaction. There is no tolerance trigger to name: the purchase receipts' went with
     * purchasing ({@code V20261113000200}).</p>
     *
     * <p>The lines of a draft are recounted in place; those of a posted one are not touched here (BR-05).</p>
     */
    @Override
    public GoodsReceipt save(GoodsReceipt receipt) {
        GoodsReceiptJpaEntity entity = jpa.findByIdWithLines(receipt.id()).orElseGet(() ->
                new GoodsReceiptJpaEntity(receipt.id(), receipt.number(), receipt.type(), receipt.productionOrderId(),
                        receipt.warehouseId(), receipt.receivedAt(), receipt.receivedBy(), receipt.deliveryNote(),
                        receipt.note()));

        Set<UUID> wanted = receipt.lines().stream().map(ReceiptLine::id).collect(Collectors.toSet());
        if (entity.getLines().removeIf(row -> !wanted.contains(row.getId())) && !entity.isNew()) {
            jpa.saveAndFlush(entity);
        }
        Map<UUID, GoodsReceiptLineJpaEntity> rows = entity.getLines().stream()
                .collect(Collectors.toMap(GoodsReceiptLineJpaEntity::getId, row -> row));
        for (ReceiptLine line : receipt.lines()) {
            GoodsReceiptLineJpaEntity row = rows.get(line.id());
            if (row == null) {
                row = new GoodsReceiptLineJpaEntity(line.id(), line.productionOrderLineId(),
                        line.inventoryItemId(), line.locationId(), BigDecimal.valueOf(line.receivedQuantity()),
                        line.lotNumber(), line.note(), line.uomConversionId());
                entity.addLine(row);
            } else if (receipt.status() == GoodsReceiptStatus.DRAFT) {
                row.recount(line.locationId(), BigDecimal.valueOf(line.receivedQuantity()), line.note(),
                        line.uomConversionId());
            }
        }
        entity.apply(receipt.status(), receipt.confirmedAt(), receipt.confirmedBy(), receipt.closedAt());
        GoodsReceiptJpaEntity saved = jpa.saveAndFlush(entity);
        // IMMEDIATE runs the pending checks now; DEFERRED again, so a later save in this transaction is
        // checked on its final totals too, not row by row.
        entityManager.createNativeQuery(
                "SET CONSTRAINTS procurement.tg_goods_receipt_lines_production_cartons IMMEDIATE").executeUpdate();
        entityManager.createNativeQuery(
                "SET CONSTRAINTS procurement.tg_goods_receipt_lines_production_cartons DEFERRED").executeUpdate();
        return toDomain(saved);
    }

    @Override
    public Map<UUID, Integer> unitsOnOtherProductionReceipts(Iterable<UUID> productionOrderLineIds,
                                                            UUID excludingReceiptId) {
        List<UUID> ids = new ArrayList<>();
        productionOrderLineIds.forEach(ids::add);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return toTotals(jpa.sumByProductionOrderLineExcluding(ids, excludingReceiptId, GoodsReceiptStatus.CANCELLED));
    }

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<GoodsReceiptJpaEntity> spec = Specification
                .<GoodsReceiptJpaEntity>where(Specs.eq("warehouseId", criteria.warehouseId()))
                .and(Specs.contains("receiptNumber", criteria.number()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : Specs.in("status", criteria.statuses()))
                .and(Specs.eq("productionOrderId", criteria.productionOrderId()))
                .and(Specs.eq("receiptType", criteria.receiptType()))
                .and(receivedBetween(criteria.receivedFrom(), criteria.receivedTo()))
                .and(com.stockflow.common.security.WarehouseScope.warehousesIn("warehouseId"));
        return jpa.findAll(spec, pageable).map(e -> new Row(e.getId(), e.getReceiptNumber(), e.getWarehouseId(),
                e.getStatus(), e.getDeliveryNote(), e.getReceivedAt(), e.getReceivedBy(), e.getConfirmedAt(),
                e.getConfirmedBy(), e.getClosedAt(), e.getReceiptType(), e.getProductionOrderId()));
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
        List<ReceiptLine> lines = e.getLines().stream().map(l -> new ReceiptLine(l.getId(),
                l.getProductionOrderLineId(), l.getInventoryItemId(), l.getLocationId(),
                l.getReceivedQty().intValueExact(), l.getLotNumber(), l.getNote(),
                l.getUomConversionId())).toList();
        return new GoodsReceipt(e.getId(), e.getReceiptNumber(), e.getProductionOrderId(), e.getWarehouseId(),
                e.getReceivedAt(), e.getReceivedBy(), e.getDeliveryNote(), e.getNote(), lines, e.getStatus(),
                e.getConfirmedAt(), e.getConfirmedBy(), e.getClosedAt(), e.getVersion());
    }
}

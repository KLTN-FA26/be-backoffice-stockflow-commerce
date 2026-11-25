package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.entity.StockPackageJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class StockPackageRepositoryAdapter implements StockPackageRepository {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockPackageJpaRepository jpa;
    private final LpnSequence lpns;

    @PersistenceContext
    private EntityManager entityManager;

    StockPackageRepositoryAdapter(StockPackageJpaRepository jpa, LpnSequence lpns) {
        this.jpa = jpa;
        this.lpns = lpns;
    }

    @Override
    public Optional<StockPackage> findById(StockPackageId id) {
        return jpa.findById(id.value()).map(StockPackageRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<StockPackage> findByLpn(String lpn) {
        return jpa.findByLpn(lpn).map(StockPackageRepositoryAdapter::toDomain);
    }

    @Override
    public List<StockPackage> findByLpns(Collection<String> lpns) {
        if (lpns.isEmpty()) {
            return List.of();
        }
        return jpa.findByLpnIn(lpns).stream().map(StockPackageRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<StockPackage> findAllById(Collection<StockPackageId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jpa.findAllById(ids.stream().map(StockPackageId::value).distinct().toList()).stream()
                .map(StockPackageRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<StockPackage> findByReceiptLines(Collection<UUID> goodsReceiptLineIds) {
        if (goodsReceiptLineIds.isEmpty()) {
            return List.of();
        }
        return jpa.findByGoodsReceiptLineIdIn(goodsReceiptLineIds).stream()
                .map(StockPackageRepositoryAdapter::toDomain).toList();
    }

    /**
     * Locked by one native {@code FOR UPDATE} in the database's id order, then refreshed: a JPA read hands back
     * whatever copy the persistence context already holds, so without the refresh the lock would guard stale
     * numbers (the same reasoning as {@code StockItemRepositoryAdapter.findByIdForUpdate}).
     */
    @Override
    public List<StockPackage> findAllByIdForUpdate(Collection<StockPackageId> ids) {
        List<UUID> wanted = ids.stream().map(StockPackageId::value).distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<UUID> locked = entityManager.createNativeQuery(
                        "select id from inventory.stock_package where id in (:ids) order by id for update")
                .setParameter("ids", wanted).getResultList();
        return locked.stream()
                .map(jpa::findById)
                .flatMap(Optional::stream)
                .map(entity -> {
                    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
                    return toDomain(entity);
                })
                .toList();
    }

    @Override
    public StockPackage save(StockPackage stockPackage) {
        StockPackageJpaEntity entity = jpa.findById(stockPackage.id().value())
                .map(existing -> {
                    existing.apply(stockPackage.receivedQty(), stockPackage.location().code(), stockPackage.status(),
                            stockPackage.closedAt());
                    return existing;
                })
                .orElseGet(() -> new StockPackageJpaEntity(stockPackage.id().value(), stockPackage.lpn(),
                        stockPackage.inventoryItemId(), stockPackage.uomConversionId(), stockPackage.nominalQty(),
                        stockPackage.receivedQty(), stockPackage.location().code(), stockPackage.status(),
                        stockPackage.goodsReceiptLineId(), stockPackage.closedAt()));
        return toDomain(jpa.save(entity));
    }

    /**
     * Native: the mark is outside the carton's mapping - it is written once, by the return receipt's confirmation, and
     * read only by the release it forbids - so no other writer of the carton carries it or can clear it
     * ({@code tg_stock_package_return_opened}).
     */
    @Override
    public void markOpenedOnReturn(Collection<StockPackageId> ids, java.time.Instant at) {
        List<UUID> wanted = ids.stream().map(StockPackageId::value).distinct().toList();
        if (wanted.isEmpty()) {
            return;
        }
        entityManager.createNativeQuery("""
                        update inventory.stock_package set return_opened_at = :at
                         where id in (:ids) and return_opened_at is null""")
                .setParameter("at", at).setParameter("ids", wanted).executeUpdate();
    }

    @Override
    public java.util.Map<String, Integer> returnedCartonsInQuarantine() {
        // A return receipt's line: RECEIPT naming the receipt itself (GOODS_RECEIPT); a purchase receipt names its line.
        List<?> rows = entityManager.createNativeQuery("""
                        select split_part(p.location_code, '-', 1) as warehouse_code, count(*) as cartons
                          from inventory.stock_package p
                         where p.status = 'IN_STOCK'
                           and exists (select 1 from inventory.stock_item s
                                        where s.stock_package_id = p.id and s.status = 'QUARANTINE' and s.on_hand > 0)
                           and exists (select 1 from inventory.stock_movement m
                                        where m.to_stock_package_id = p.id and m.movement_type = 'RECEIPT'
                                          and m.reference_type = 'GOODS_RECEIPT')
                         group by split_part(p.location_code, '-', 1)""").getResultList();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            counts.put((String) columns[0], ((Number) columns[1]).intValue());
        }
        return counts;
    }

    @Override
    public java.util.Optional<java.time.Instant> returnOpenedAt(StockPackageId id) {
        List<?> found = entityManager.createNativeQuery("""
                        select return_opened_at from inventory.stock_package
                         where id = :id and return_opened_at is not null""")
                .setParameter("id", id.value()).getResultList();
        if (found.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object value = found.getFirst();
        return java.util.Optional.of(value instanceof java.time.Instant instant ? instant
                : value instanceof java.time.OffsetDateTime offset ? offset.toInstant()
                : ((java.sql.Timestamp) value).toInstant());
    }

    @Override
    public boolean openedOnReturn(StockPackageId id) {
        return !entityManager.createNativeQuery("""
                        select 1 from inventory.stock_package where id = :id and return_opened_at is not null""")
                .setParameter("id", id.value()).getResultList().isEmpty();
    }

    /**
     * A native query through the entity manager, not JDBC: it flushes this transaction's pending stock rows first,
     * so a caller reading right after a receipt sees what the receipt put into the cartons.
     */
    @Override
    public Map<StockPackageId, Integer> onHandOf(Collection<StockPackageId> ids) {
        List<UUID> wanted = ids.stream().map(StockPackageId::value).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select stock_package_id, sum(on_hand) from inventory.stock_item
                         where stock_package_id in (:ids) group by stock_package_id""")
                .setParameter("ids", wanted).getResultList();
        Map<StockPackageId, Integer> onHand = new HashMap<>();
        for (Object[] row : rows) {
            onHand.put(new StockPackageId((UUID) row[0]), Math.toIntExact(((Number) row[1]).longValue()));
        }
        return onHand;
    }

    @Override
    public Map<StockPackageId, String> lotsOf(Collection<StockPackageId> ids) {
        List<UUID> wanted = ids.stream().map(StockPackageId::value).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select stock_package_id, lot_number from inventory.stock_item
                         where stock_package_id in (:ids) and lot_number is not null""")
                .setParameter("ids", wanted).getResultList();
        Map<StockPackageId, String> lots = new HashMap<>();
        for (Object[] row : rows) {
            lots.put(new StockPackageId((UUID) row[0]), (String) row[1]);
        }
        return lots;
    }

    @Override
    public java.util.OptionalInt cartonFactorOf(String sku) {
        @SuppressWarnings("unchecked")
        List<Number> factors = entityManager.createNativeQuery("""
                        select u.factor from inventory.uom_conversions u
                          join inventory.inventory_items i on i.id = u.inventory_item_id
                         where i.sku = :sku and u.active""")
                .setParameter("sku", sku).getResultList();
        return factors.size() == 1 ? java.util.OptionalInt.of(factors.getFirst().intValue())
                : java.util.OptionalInt.empty();
    }

    /**
     * The same read as {@link #onHandOf}, restricted to INBOUND rows: a native query through the entity manager, so
     * pending stock rows are flushed first.
     */
    @Override
    public Map<StockPackageId, Integer> inboundOnHandOf(Collection<StockPackageId> ids) {
        List<UUID> wanted = ids.stream().map(StockPackageId::value).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select stock_package_id, sum(on_hand) from inventory.stock_item
                         where stock_package_id in (:ids) and status = 'INBOUND' group by stock_package_id""")
                .setParameter("ids", wanted).getResultList();
        Map<StockPackageId, Integer> onHand = new HashMap<>();
        for (Object[] row : rows) {
            onHand.put(new StockPackageId((UUID) row[0]), Math.toIntExact(((Number) row[1]).longValue()));
        }
        return onHand;
    }

    /**
     * The block is drawn by {@link LpnSequence} in its own transaction, never in the caller's: cartons are labelled
     * under a SKU's lock, and a sequence row held until the caller commits would let two receipts counting several
     * SKUs deadlock. One block per call, not one number per carton, so the second connection that transaction needs
     * is borrowed once. A rolled-back count leaves a gap; {@code uk_stock_package_lpn} still forbids a duplicate.
     */
    @Override
    public List<String> nextLpns(LocalDate day, int count) {
        long last = lpns.reserve(day, count);
        String prefix = "LPN-" + day.format(DAY) + "-";
        return java.util.stream.LongStream.rangeClosed(last - count + 1, last)
                .mapToObj(number -> prefix + "%06d".formatted(number))
                .toList();
    }

    private static StockPackage toDomain(StockPackageJpaEntity entity) {
        return new StockPackage(new StockPackageId(entity.getId()), entity.getLpn(), entity.getInventoryItemId(),
                entity.getUomConversionId(), entity.getNominalQty(), entity.getReceivedQty(),
                new LocationId(entity.getLocationCode()), entity.getStatus(),
                entity.getGoodsReceiptLineId(), entity.getClosedAt(), entity.getVersion());
    }
}

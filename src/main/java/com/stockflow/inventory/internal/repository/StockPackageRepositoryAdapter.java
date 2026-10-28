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
                            stockPackage.unopened(), stockPackage.closedAt());
                    return existing;
                })
                .orElseGet(() -> new StockPackageJpaEntity(stockPackage.id().value(), stockPackage.lpn(),
                        stockPackage.inventoryItemId(), stockPackage.uomConversionId(), stockPackage.nominalQty(),
                        stockPackage.receivedQty(), stockPackage.location().code(), stockPackage.status(),
                        stockPackage.unopened(), stockPackage.goodsReceiptLineId(), stockPackage.closedAt()));
        return toDomain(jpa.save(entity));
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
                new LocationId(entity.getLocationCode()), entity.getStatus(), entity.isUnopened(),
                entity.getGoodsReceiptLineId(), entity.getClosedAt(), entity.getVersion());
    }
}

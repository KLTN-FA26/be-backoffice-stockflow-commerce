package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.entity.StockPackageJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
    private final NamedParameterJdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    StockPackageRepositoryAdapter(StockPackageJpaRepository jpa, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
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
     * The upsert of {@code CycleCountRepositoryAdapter.nextNumber}: its row lock serialises two receipts labelling
     * cartons on one day, and the number never repeats ({@code uk_stock_package_lpn}).
     */
    @Override
    public String nextLpn(LocalDate day) {
        Number sequence = jdbc.queryForObject("""
                INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                VALUES ('LPN', :day, 1)
                ON CONFLICT (document_type, sequence_date)
                DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                RETURNING last_value""", new MapSqlParameterSource("day", day), Number.class);
        return "LPN-%s-%06d".formatted(day.format(DAY), sequence.longValue());
    }

    private static StockPackage toDomain(StockPackageJpaEntity entity) {
        return new StockPackage(new StockPackageId(entity.getId()), entity.getLpn(), entity.getInventoryItemId(),
                entity.getUomConversionId(), entity.getNominalQty(), entity.getReceivedQty(),
                new LocationId(entity.getLocationCode()), entity.getStatus(), entity.isUnopened(),
                entity.getGoodsReceiptLineId(), entity.getClosedAt(), entity.getVersion());
    }
}

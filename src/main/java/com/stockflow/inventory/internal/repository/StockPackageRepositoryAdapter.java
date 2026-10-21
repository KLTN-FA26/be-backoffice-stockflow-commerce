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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class StockPackageRepositoryAdapter implements StockPackageRepository {

    private final StockPackageJpaRepository jpa;

    @PersistenceContext
    private EntityManager entityManager;

    StockPackageRepositoryAdapter(StockPackageJpaRepository jpa) {
        this.jpa = jpa;
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

    private static StockPackage toDomain(StockPackageJpaEntity entity) {
        return new StockPackage(new StockPackageId(entity.getId()), entity.getLpn(), entity.getInventoryItemId(),
                entity.getUomConversionId(), entity.getNominalQty(), entity.getReceivedQty(),
                new LocationId(entity.getLocationCode()), entity.getStatus(), entity.isUnopened(),
                entity.getGoodsReceiptLineId(), entity.getClosedAt(), entity.getVersion());
    }
}

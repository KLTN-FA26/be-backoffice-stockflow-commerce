package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.PutawayStatus;
import com.stockflow.warehouse.internal.domain.PutawayTask;
import com.stockflow.warehouse.internal.domain.PutawayTaskRepository;
import com.stockflow.warehouse.internal.entity.PutawayTaskJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Implements the {@link PutawayTaskRepository} port on Spring Data. */
@Repository
class PutawayTaskRepositoryAdapter implements PutawayTaskRepository {

    private static final Set<PutawayStatus> OPEN = Set.of(PutawayStatus.PENDING, PutawayStatus.IN_PROGRESS);

    private final PutawayTaskJpaRepository jpa;

    @PersistenceContext
    private EntityManager entityManager;

    PutawayTaskRepositoryAdapter(PutawayTaskJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<PutawayTask> findById(UUID id) {
        return jpa.findById(id).map(PutawayTaskRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    /**
     * Native {@code select id ... for update}, then a refresh: a plain {@code findById} would hand back the copy
     * already in the persistence context, and the lock would guard a row the caller never re-read (the same
     * reasoning as {@code StockPackageRepositoryAdapter.findAllByIdForUpdate}).
     */
    @Override
    public Optional<PutawayTask> findByIdForUpdate(UUID id) {
        @SuppressWarnings("unchecked")
        List<UUID> locked = entityManager.createNativeQuery(
                        "select id from warehouse.putaway_task where id = :id for update")
                .setParameter("id", id).getResultList();
        return locked.stream().findFirst().flatMap(jpa::findById).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return toDomain(entity);
        });
    }

    @Override
    public Optional<PutawayTask> findOpenByStockPackage(UUID stockPackageId) {
        return jpa.findFirstByStockPackageIdAndStatusIn(stockPackageId, OPEN)
                .map(PutawayTaskRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<PutawayTask> findLooseOfReceiptLine(UUID goodsReceiptLineId) {
        return jpa.findFirstByGoodsReceiptLineIdAndStockPackageIdIsNull(goodsReceiptLineId)
                .map(PutawayTaskRepositoryAdapter::toDomain);
    }

    @Override
    public List<PutawayTask> findByReceipt(UUID goodsReceiptId) {
        return jpa.findByGoodsReceiptIdOrderByIdAsc(goodsReceiptId).stream()
                .map(PutawayTaskRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<PutawayTask> findOpen() {
        return jpa.findByStatusInOrderByIdAsc(OPEN).stream().map(PutawayTaskRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Map<UUID, Integer> doneQuantityByReceiptLine(UUID goodsReceiptId) {
        Map<UUID, Integer> done = new HashMap<>();
        for (Object[] row : jpa.sumDoneByReceiptLine(goodsReceiptId)) {
            done.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return done;
    }

    /** Load-and-copy for an existing task, so the version and audit columns survive; a new one is inserted. */
    @Override
    public PutawayTask save(PutawayTask task) {
        PutawayTaskJpaEntity entity = jpa.findById(task.id())
                .map(existing -> {
                    existing.apply(task.quantity(), task.targetLocationId(), task.status());
                    return existing;
                })
                .orElseGet(() -> new PutawayTaskJpaEntity(task.id(), task.goodsReceiptId(),
                        task.goodsReceiptLineId(), task.sku(), task.quantity(), task.stockPackageId(),
                        task.targetLocationId(), task.status()));
        return toDomain(jpa.saveAndFlush(entity));
    }

    private static PutawayTask toDomain(PutawayTaskJpaEntity e) {
        return new PutawayTask(e.getId(), e.getGoodsReceiptId(), e.getGoodsReceiptLineId(), e.getSku(),
                e.getQuantity(), e.getStockPackageId(), e.getTargetLocationId(), e.getStatus());
    }
}

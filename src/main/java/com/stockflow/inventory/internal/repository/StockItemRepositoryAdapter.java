package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.StockItemJpaEntity;

import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.common.domain.Sku;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter: implements the domain port on top of Spring Data.
 *
 * <p>This class is the only place in the module that knows both languages. Everything above it
 * sees {@code StockItem} and {@code Sku}; everything below sees entities and UUIDs. That is what
 * keeps {@code Pageable}, {@code Optional&lt;Entity&gt;} and lock modes out of the domain.</p>
 */
@Repository
class StockItemRepositoryAdapter implements StockItemRepository {

    /**
     * Three seconds. Long enough to ride out a normal checkout ahead in the queue, short enough
     * that a stuck transaction surfaces as an error rather than as a request that never returns.
     * See {@link #findByIdForUpdate} on which databases honour it.
     */
    private static final java.util.Map<String, Object> LOCK_TIMEOUT =
            java.util.Map.of("jakarta.persistence.lock.timeout", 3000);

    private final StockItemJpaRepository jpa;

    /**
     * Needed for {@code refresh}, which Spring Data does not expose. See
     * {@link #findByIdForUpdate(StockItemId)} — without it the pessimistic lock would be
     * decorative.
     *
     * <p>Injected through the constructor rather than {@code @PersistenceContext}: the bean Spring
     * supplies is the same shared, transaction-aware proxy either way, and constructor injection
     * keeps the dependency visible and the class constructible in a test.</p>
     */
    private final EntityManager entityManager;

    StockItemRepositoryAdapter(StockItemJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<StockItem> findById(StockItemId id) {
        return jpa.findByIdWithReservations(id.value()).map(StockItemPersistenceMapper::toDomain);
    }

    /**
     * Loads the aggregate under a database write lock, with genuinely current state.
     *
     * <p><b>The {@code refresh} call is the load-bearing part, and it is easy to leave out.</b> A
     * JPQL query with {@code @Lock(PESSIMISTIC_WRITE)} does take the lock, but if the entity is
     * already in the persistence context — and inside a checkout it very often is — Hibernate
     * returns the cached instance and discards the row it just read. The transaction would then be
     * correctly serialised and still reserve against pre-lock numbers, which is the oversell the
     * lock was taken to prevent.</p>
     *
     * <p>A scalar SELECT FOR UPDATE first locks the row before loading any entity version.
     * Hibernate's follow-on locking of a fetched collection can otherwise read an old version,
     * wait for a concurrent checkout, then reject that version instead of refreshing current stock.
     * {@code refresh(entity, PESSIMISTIC_WRITE)} then re-reads the row and its cascaded collection
     * under the lock and overwrites the cached state, so the aggregate handed back reflects what
     * every earlier transaction committed. The load-then-refresh pair also keeps the lock and the
     * fetch join apart, which not every database supports combining.</p>
     *
     * <p>The lock timeout is passed as a property because {@code refresh} does not inherit the
     * {@code @QueryHints} a Spring Data locking query would carry. Be aware of what it does and
     * does not buy on PostgreSQL: the dialect only renders the sentinel values as SQL
     * ({@code NOWAIT}, {@code SKIP LOCKED}), so a numeric timeout is not turned into a
     * {@code FOR UPDATE} clause. What actually bounds a contended wait here is the server-side
     * {@code lock_timeout} set in {@code docker-compose.yml}. The hint is kept because it is
     * portable and would take effect on a database whose dialect supports it — but the operational
     * guarantee lives in the database configuration, not in this line.</p>
     */
    @Override
    public Optional<StockItem> findByIdForUpdate(StockItemId id) {
        if (entityManager.createNativeQuery("select id from inventory.stock_item where id = :id for update")
                .setParameter("id", id.value()).getResultList().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findByIdWithReservations(id.value())
                .map(entity -> {
                    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, LOCK_TIMEOUT);
                    return StockItemPersistenceMapper.toDomain(entity);
                });
    }

    @Override
    public List<StockItem> findWithReservationsForRequest(UUID rootRequestId) {
        return jpa.findStockItemIdsForRequest(rootRequestId).stream()
                .map(id -> findByIdForUpdate(new StockItemId(id)))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<StockAllocator.Candidate> findAvailabilityBySku(Sku sku) {
        return jpa.findAvailabilityBySku(sku.code()).stream()
                .map(AvailabilityRow::toCandidate)
                .toList();
    }

    @Override
    public List<StockItem> findAvailableBySku(Sku sku) {
        return jpa.findAvailableBySku(sku.code()).stream()
                .map(StockItemPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public java.util.Map<String, Long> availableQuantities(java.util.Set<String> skus, LocalDate today) {
        if (skus.isEmpty()) return java.util.Map.of();
        return jpa.availableTotals(skus, today).stream().collect(java.util.stream.Collectors.toMap(
                StockItemJpaRepository.AvailableTotal::getSku, StockItemJpaRepository.AvailableTotal::getQuantity));
    }

    @Override
    public List<StockLevelLine> findLevelLinesBySku(Sku sku) {
        return jpa.findLevelRowsBySku(sku.code()).stream()
                .map(StockLevelRow::toLine)
                .toList();
    }

    @Override
    public Map<Sku, Integer> sumAvailableBySkus(Collection<Sku> skus, LocalDate today) {
        if (skus.isEmpty()) {
            return Map.of();      // "in ()" is not valid SQL, and there is nothing to ask
        }
        Map<Sku, Integer> totals = new HashMap<>();
        for (SkuAtpRow row : jpa.sumAvailableBySkus(skus.stream().map(Sku::code).toList(), today)) {
            totals.put(new Sku(row.sku()), Math.toIntExact(row.atp()));
        }
        return totals;
    }

    @Override
    public List<StockItem> findBySkuAndLocation(Sku sku, LocationId location) {
        return jpa.findBySkuAndLocation(sku.code(), location.code()).stream()
                .map(StockItemPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<StockItem> findWithReservationsExpiredBefore(Instant cutoff, int limit) {
        return jpa.findStockItemIdsWithExpiredReservations(cutoff, PageRequest.of(0, limit)).stream()
                .map(jpa::findByIdWithReservations)
                .flatMap(Optional::stream)
                .map(StockItemPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<StockItem> findByReservationIdForUpdate(ReservationId reservationId) {
        return jpa.findStockItemIdByReservationId(reservationId.value())
                .flatMap(id -> findByIdForUpdate(new StockItemId(id)));
    }

    @Override
    public List<StockItem> findWithReservationsForProductionOrder(UUID productionOrderId) {
        return jpa.findStockItemIdsHoldingProductionOrder(productionOrderId).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .map(id -> findByIdForUpdate(new StockItemId(id)))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<StockItemId> findIdByReservationId(ReservationId reservationId) {
        return jpa.findStockItemIdByReservationId(reservationId.value()).map(StockItemId::new);
    }

    /**
     * The hold changes parent by an UPDATE of its {@code stock_item_id}. Going through the two collections
     * would have {@code orphanRemoval} delete the row under the source while the destination inserted it
     * again — a primary-key clash at best, and a DELETE the foreign keys from
     * {@code ordering.order_line_reservation} and fulfilment rightly refuse. Both parents are refreshed after
     * the UPDATE so their collections match the rows, then saved as usual for {@code on_hand} and
     * {@code reserved}.
     */
    /**
     * The marker is a transaction-local setting naming the row ({@code inventory.enforce_sku_policy},
     * V20261014000450); it is set just before this row's UPDATE is flushed and cleared right after, so no other
     * row and no later statement of the transaction is covered by it.
     */
    @Override
    public StockItem saveMerged(StockItem stockItem) {
        entityManager.flush();
        mergeMarker(stockItem.id().value().toString());
        try {
            StockItem saved = save(stockItem);
            entityManager.flush();
            return saved;
        } finally {
            mergeMarker("");
        }
    }

    private void mergeMarker(String value) {
        entityManager.createNativeQuery("SELECT set_config('stockflow.same_receipt_merge', :value, true)")
                .setParameter("value", value).getSingleResult();
    }

    @Override
    public void saveHandOver(StockItem from, StockItem to, ReservationId moved) {
        boolean merged = jpa.existsById(to.id().value());
        if (!merged) {
            StockItemJpaEntity fresh = StockItemPersistenceMapper.toNewEntity(to);
            fresh.getReservations().removeIf(r -> r.getId().equals(moved.value()));
            jpa.save(fresh);
        }
        entityManager.flush();
        int updated = entityManager.createNativeQuery(
                        "update inventory.stock_reservation set stock_item_id = :to where id = :id and stock_item_id = :from")
                .setParameter("to", to.id().value())
                .setParameter("id", moved.value())
                .setParameter("from", from.id().value())
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("Reservation %s is no longer on stock item %s".formatted(moved, from.id()));
        }
        for (StockItem item : List.of(from, to)) {
            jpa.findByIdWithReservations(item.id().value()).ifPresent(entityManager::refresh);
        }
        save(from);
        if (merged) {
            saveMerged(to);
        } else {
            save(to);
        }
    }

    @Override
    public List<StockItem> findWithReservationsForOrder(UUID orderId) {
        return jpa.findStockItemIdsHoldingOrder(orderId).stream()
                .map(id -> findByIdForUpdate(new StockItemId(id)))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Insert or update.
     *
     * <p>The aggregate carries its own id from creation, so {@code save()} cannot tell new from
     * existing the way a generated id would. Looking the row up first and copying onto it keeps
     * Hibernate's dirty checking and the {@code @Version} column working — a blind
     * {@code jpa.save(toNewEntity(item))} would detach and re-merge, losing the version and
     * silently disabling optimistic locking.</p>
     */
    @Override
    public StockItem save(StockItem stockItem) {
        Optional<StockItemJpaEntity> existing = jpa.findByIdWithReservations(stockItem.id().value());
        if (existing.isPresent()) {
            StockItemJpaEntity managed = existing.get();
            StockItemPersistenceMapper.applyToEntity(stockItem, managed);
            return StockItemPersistenceMapper.toDomain(jpa.save(managed));
        }
        return StockItemPersistenceMapper.toDomain(
                jpa.save(StockItemPersistenceMapper.toNewEntity(stockItem)));
    }
}

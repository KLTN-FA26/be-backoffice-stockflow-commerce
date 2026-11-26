package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.HeldStock;
import com.stockflow.inventory.internal.domain.HoldDetail;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.entity.ReservationJpaEntity;
import com.stockflow.inventory.internal.entity.StockPackageJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Adapter: implements the carton's port on top of Spring Data.
 *
 * <p>The only place in the module that knows both languages: everything above sees {@code StockPackage} and
 * {@code Sku}; everything below sees entities and UUIDs. The mapping is hand-written on purpose: rehydration goes
 * through the full domain constructor so the invariants are re-checked, and the carton's SKU and lot number are read
 * through other tables ({@code inventory_items}, {@code stock_lot}), not columns of it.</p>
 *
 * <p>The unlocked reads of whole cartons are {@code @Transactional(readOnly = true)}: a carton is mapped with its
 * allocations, a lazy collection, and a caller outside a transaction (a default method of a service interface calls
 * its sibling past the proxy) would otherwise map a carton whose session is already closed. Inside a caller's
 * transaction the annotation only joins it.</p>
 *
 * <p>The projections are native SQL through the entity manager, never JDBC: a carton has no {@code sku} column to
 * filter on in JPQL, and a native query through the entity manager still flushes this transaction's pending cartons
 * first, so a caller reading right after a write sees it.</p>
 */
@Repository
class StockPackageRepositoryAdapter implements StockPackageRepository {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * Three seconds. Long enough to ride out a normal release ahead in the queue, short enough that a stuck
     * transaction surfaces as an error. PostgreSQL's dialect renders only the sentinel values (NOWAIT, SKIP LOCKED), so
     * what actually bounds a contended wait is the server-side {@code lock_timeout} of {@code docker-compose.yml}; the
     * hint is kept because it is portable.
     */
    private static final Map<String, Object> LOCK_TIMEOUT = Map.of("jakarta.persistence.lock.timeout", 3000);

    /** A free carton: sellable, and no live allocation names it ({@code uk_stock_reservation_live_carton}). */
    private static final String FREE = """
            p.stock_status = 'AVAILABLE'
            and not exists (select 1 from inventory.stock_reservation a
                             where a.stock_package_id = p.id and a.status = 'HELD')""";

    private final StockPackageJpaRepository jpa;
    private final LpnSequence lpns;
    /**
     * Needed for {@code refresh}, which Spring Data does not expose - without it the pessimistic lock would be
     * decorative ({@link #findAllByIdForUpdate}). Constructor-injected: the same shared, transaction-aware proxy
     * {@code @PersistenceContext} would give, visible and constructible in a test.
     */
    private final EntityManager entityManager;

    StockPackageRepositoryAdapter(StockPackageJpaRepository jpa, LpnSequence lpns, EntityManager entityManager) {
        this.jpa = jpa;
        this.lpns = lpns;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockPackage> findById(StockPackageId id) {
        return jpa.findByIdWithReservations(id.value()).map(StockPackageRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockPackage> findByLpn(String lpn) {
        return jpa.findByLpn(lpn).map(StockPackageRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPackage> findByLpns(Collection<String> lpns) {
        if (lpns.isEmpty()) {
            return List.of();
        }
        return jpa.findByLpnIn(lpns).stream().map(StockPackageRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPackage> findAllById(Collection<StockPackageId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jpa.findAllById(ids.stream().map(StockPackageId::value).distinct().toList()).stream()
                .map(StockPackageRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPackage> findByReceiptLines(Collection<UUID> goodsReceiptLineIds) {
        if (goodsReceiptLineIds.isEmpty()) {
            return List.of();
        }
        return jpa.findByGoodsReceiptLineIdIn(goodsReceiptLineIds).stream()
                .map(StockPackageRepositoryAdapter::toDomain).toList();
    }

    /**
     * Locked by one native {@code FOR UPDATE} in the database's id order, then refreshed.
     *
     * <p><b>The {@code refresh} is the load-bearing part, and it is easy to leave out.</b> A JPA read hands back
     * whatever copy the persistence context already holds, so a carton read before the lock - and inside a release it
     * very often was - would come back with its pre-lock state: the transactions correctly serialised, and the second
     * still allocating a carton the first just took. The scalar {@code FOR UPDATE} locks the rows before any entity
     * version is loaded; {@code refresh(entity, PESSIMISTIC_WRITE)} then re-reads each carton and its cascaded
     * allocations under the lock and overwrites the cached state.</p>
     *
     * <p>The native query flushes this transaction's pending changes first, so a carton changed and saved earlier in
     * the transaction is refreshed to what was saved, not to what was committed before it.</p>
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
                .map(jpa::findByIdWithReservations)
                .flatMap(Optional::stream)
                .map(entity -> {
                    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, LOCK_TIMEOUT);
                    return toDomain(entity);
                })
                .toList();
    }

    /**
     * Insert or update.
     *
     * <p>The aggregate carries its own id from creation, so {@code save()} cannot tell new from existing the way a
     * generated id would. Looking the row up first and copying onto it keeps Hibernate's dirty checking and the
     * {@code @Version} column working — a blind save of a new entity would detach and re-merge, losing the version.
     * Replacing the whole child collection is safe because a carton carries a bounded number of allocations (one live,
     * and the closed ones of its history).</p>
     */
    @Override
    public StockPackage save(StockPackage stockPackage) {
        StockPackageJpaEntity entity = jpa.findByIdWithReservations(stockPackage.id().value())
                .orElseGet(() -> new StockPackageJpaEntity(stockPackage.id().value(), stockPackage.lpn(),
                        stockPackage.inventoryItemId(), stockPackage.sku().code(), stockPackage.uomConversionId(),
                        stockPackage.nominalQty(), stockPackage.goodsReceiptLineId()));
        entity.apply(stockPackage.countedAt(), stockPackage.location().code(), stockPackage.status(),
                stockPackage.stockStatus(), stockPackage.lotId(), stockPackage.lotNumber(), stockPackage.receivedAt(),
                stockPackage.closedAt());
        entity.replaceReservations(toEntities(stockPackage));
        return toDomain(jpa.save(entity));
    }

    @Override
    public StockPackage saveNow(StockPackage stockPackage) {
        StockPackage saved = save(stockPackage);
        entityManager.flush();
        return saved;
    }

    // ------------------------------------------------------------------ allocations

    @Override
    public Optional<StockPackageId> findIdByReservationId(ReservationId reservationId) {
        return jpa.findIdByReservationId(reservationId.value()).map(StockPackageId::new);
    }

    @Override
    public List<StockPackage> findWithReservationsForRequest(UUID rootRequestId) {
        return findAllByIdForUpdate(jpa.findIdsHoldingRequest(rootRequestId).stream().map(StockPackageId::new)
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPackage> findHoldingRequest(UUID rootRequestId) {
        return jpa.findIdsHoldingRequest(rootRequestId).stream()
                .map(id -> jpa.findByIdWithReservations(id).map(StockPackageRepositoryAdapter::toDomain))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPackage> findReservedByRequest(UUID rootRequestId) {
        return jpa.findIdsEverReservedByRequest(rootRequestId).stream()
                .map(id -> jpa.findByIdWithReservations(id).map(StockPackageRepositoryAdapter::toDomain))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<HeldStock> findHeldForOrder(UUID orderId) {
        return jpa.findHeldForOrder(orderId);
    }

    @Override
    public List<HoldDetail> findHolds(Collection<UUID> reservationIds) {
        return reservationIds.isEmpty() ? List.of() : jpa.findHolds(reservationIds);
    }

    // ------------------------------------------------------------------ what is there

    /**
     * Scalar rows, so nothing lands in the persistence context: entities loaded here would shadow the
     * {@code SELECT ... FOR UPDATE} that follows, and the re-check under the lock would run against pre-lock data.
     */
    @Override
    public List<StockAllocator.Candidate> findAvailabilityBySku(Sku sku) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select p.id, p.location_code, l.lot_number, p.nominal_qty, p.received_at
                          from inventory.stock_package p
                          join inventory.inventory_items i on i.id = p.inventory_item_id
                          left join inventory.stock_lot l on l.id = p.lot_id
                         where i.sku = :sku and\s""" + FREE)
                .setParameter("sku", sku.code()).getResultList();
        return rows.stream().map(row -> new StockAllocator.Candidate(new StockPackageId((UUID) row[0]),
                new LocationId((String) row[1]), (String) row[2], StockStatus.AVAILABLE,
                ((Number) row[3]).intValue(), instant(row[4]))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPackage> findAvailableBySku(Sku sku) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("""
                        select p.id from inventory.stock_package p
                          join inventory.inventory_items i on i.id = p.inventory_item_id
                         where i.sku = :sku and p.stock_status = 'AVAILABLE'""")
                .setParameter("sku", sku.code()).getResultList();
        return findAllById(ids.stream().map(StockPackageId::new).toList());
    }

    @Override
    public List<Allocatable> findAllocatable(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();      // "in ()" is not valid SQL, and there is nothing to ask
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select p.id, i.sku, p.location_code, p.nominal_qty
                          from inventory.stock_package p
                          join inventory.inventory_items i on i.id = p.inventory_item_id
                         where i.sku in (:skus) and\s""" + FREE)
                .setParameter("skus", List.copyOf(skus)).getResultList();
        return rows.stream().map(row -> new Allocatable(new StockPackageId((UUID) row[0]), (String) row[1],
                (String) row[2], ((Number) row[3]).intValue())).toList();
    }

    /** Units, as the levels always were: a carton in stock is its count, a held one its count reserved. */
    @Override
    public List<StockLevelLine> findLevelLinesBySku(Sku sku) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select l.location_code, l.stock_status, sum(l.nominal_qty),
                               coalesce(sum(l.nominal_qty) filter (where l.held), 0),
                               count(*), count(*) filter (where l.held)
                          from (select p.location_code, p.stock_status, p.nominal_qty,
                                       exists (select 1 from inventory.stock_reservation a
                                                where a.stock_package_id = p.id and a.status = 'HELD') as held
                                  from inventory.stock_package p
                                  join inventory.inventory_items i on i.id = p.inventory_item_id
                                 where i.sku = :sku and p.status = 'IN_STOCK') l
                         group by l.location_code, l.stock_status""")
                .setParameter("sku", sku.code()).getResultList();
        return rows.stream().map(row -> new StockLevelLine(new LocationId((String) row[0]),
                StockStatus.valueOf((String) row[1]), ((Number) row[2]).intValue(), ((Number) row[3]).intValue(),
                ((Number) row[4]).intValue(), ((Number) row[5]).intValue()))
                .toList();
    }

    @Override
    public Map<String, Allocated> findAllocatedBySku(Sku sku) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select p.location_code, sum(r.cartons * r.units_per_carton), sum(r.cartons)
                          from inventory.stock_reservation r
                          join inventory.stock_package p on p.id = r.stock_package_id
                         where r.sku = :sku and r.status = 'HELD' and r.allocated_from_id is not null
                         group by p.location_code""")
                .setParameter("sku", sku.code()).getResultList();
        Map<String, Allocated> allocated = new TreeMap<>();
        rows.forEach(row -> allocated.put((String) row[0],
                new Allocated(((Number) row[1]).intValue(), ((Number) row[2]).intValue())));
        return allocated;
    }

    @Override
    public Optional<StockStatus> stockStatusOf(StockPackageId id) {
        List<?> found = entityManager.createNativeQuery(
                        "select stock_status from inventory.stock_package where id = :id and stock_status is not null")
                .setParameter("id", id.value()).getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(StockStatus.valueOf((String) found.getFirst()));
    }

    // ------------------------------------------------------------------ returns

    /**
     * Native: the mark is outside the carton's mapping - it is written once, by the return receipt's confirmation, and
     * read only by the release it forbids - so no other writer of the carton carries it or can clear it
     * ({@code tg_stock_package_return_opened}).
     */
    @Override
    public void markOpenedOnReturn(Collection<StockPackageId> ids, Instant at) {
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
    public Map<String, Integer> returnedCartonsInQuarantine() {
        // A return receipt's line: RECEIPT naming the receipt itself (GOODS_RECEIPT); a purchase receipt names its line.
        List<?> rows = entityManager.createNativeQuery("""
                        select split_part(p.location_code, '-', 1) as warehouse_code, count(*) as cartons
                          from inventory.stock_package p
                         where p.stock_status = 'QUARANTINE'
                           and exists (select 1 from inventory.stock_movement m
                                        where m.stock_package_id = p.id and m.movement_type = 'RECEIPT'
                                          and m.reference_type = 'GOODS_RECEIPT')
                         group by split_part(p.location_code, '-', 1)""").getResultList();
        Map<String, Integer> counts = new TreeMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            counts.put((String) columns[0], ((Number) columns[1]).intValue());
        }
        return counts;
    }

    @Override
    public Optional<Instant> returnOpenedAt(StockPackageId id) {
        List<?> found = entityManager.createNativeQuery("""
                        select return_opened_at from inventory.stock_package
                         where id = :id and return_opened_at is not null""")
                .setParameter("id", id.value()).getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(instant(found.getFirst()));
    }

    @Override
    public boolean openedOnReturn(StockPackageId id) {
        return !entityManager.createNativeQuery("""
                        select 1 from inventory.stock_package where id = :id and return_opened_at is not null""")
                .setParameter("id", id.value()).getResultList().isEmpty();
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

    // ------------------------------------------------------------------ mapping

    private static StockPackage toDomain(StockPackageJpaEntity entity) {
        List<Reservation> reservations = entity.getReservations().stream()
                .map(StockPackageRepositoryAdapter::toDomain).toList();
        return new StockPackage(new StockPackageId(entity.getId()), entity.getLpn(), new Sku(entity.getSku()),
                entity.getInventoryItemId(), entity.getUomConversionId(), entity.getNominalQty(),
                entity.getCountedAt(), new LocationId(entity.getLocationCode()), entity.getStatus(),
                entity.getStockStatus(), entity.getLotId(), entity.getLotNumber(), entity.getReceivedAt(),
                entity.getGoodsReceiptLineId(), entity.getClosedAt(), reservations, entity.getVersion());
    }

    private static Reservation toDomain(ReservationJpaEntity entity) {
        return new Reservation(new ReservationId(entity.getId()), entity.getOrderId(), entity.getRootRequestId(),
                entity.getRequestId(), entity.getUnitsPerCarton(), entity.getReservedAt(), entity.getStatus(),
                entity.getReleaseReason(), entity.getClosedAt(), entity.getAllocatedFrom());
    }

    private static List<ReservationJpaEntity> toEntities(StockPackage carton) {
        return carton.reservations().stream()
                .map(r -> new ReservationJpaEntity(r.id().value(), carton.sku().code(), r.orderId(),
                        r.rootRequestId(), r.requestId(), 1, r.unitsPerCarton(), r.reservedAt(), r.status(),
                        r.releaseReason(), r.closedAt(), r.allocatedFrom()))
                .toList();
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Instant at ? at
                : value instanceof OffsetDateTime offset ? offset.toInstant()
                : ((java.sql.Timestamp) value).toInstant();
    }
}

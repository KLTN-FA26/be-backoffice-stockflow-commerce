package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.PutawayTrip;
import com.stockflow.warehouse.internal.domain.PutawayTripRepository;
import com.stockflow.warehouse.internal.domain.PutawayTripStatus;
import com.stockflow.warehouse.internal.entity.PutawayTripItemJpaEntity;
import com.stockflow.warehouse.internal.entity.PutawayTripJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implements the {@link PutawayTripRepository} port: the trip row and its carton rows are read and written together,
 * so the aggregate is never seen half loaded.
 */
@Repository
class PutawayTripRepositoryAdapter implements PutawayTripRepository {

    private static final Set<PutawayTripStatus> OPEN = Set.of(PutawayTripStatus.LOADING,
            PutawayTripStatus.IN_PROGRESS);

    private final PutawayTripJpaRepository trips;
    private final PutawayTripItemJpaRepository items;

    @PersistenceContext
    private EntityManager entityManager;

    PutawayTripRepositoryAdapter(PutawayTripJpaRepository trips, PutawayTripItemJpaRepository items) {
        this.trips = trips;
        this.items = items;
    }

    @Override
    public Optional<PutawayTrip> findById(UUID id) {
        return trips.findById(id).map(this::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return trips.existsById(id);
    }

    /**
     * Native {@code select id ... for update}, then a refresh of the trip and of its cartons: a plain {@code findById}
     * would hand back the copies already in the persistence context, and the lock would guard rows the caller never
     * re-read (as {@code PutawayTaskRepositoryAdapter.findByIdForUpdate}).
     */
    @Override
    public Optional<PutawayTrip> findByIdForUpdate(UUID id) {
        @SuppressWarnings("unchecked")
        List<UUID> locked = entityManager.createNativeQuery(
                        "select id from warehouse.putaway_trip where id = :id for update")
                .setParameter("id", id).getResultList();
        return locked.stream().findFirst().flatMap(trips::findById).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            List<PutawayTripItemJpaEntity> cartons = items.findByTripIdOrderByIdAsc(id);
            cartons.forEach(entityManager::refresh);
            return toDomain(entity, cartons);
        });
    }

    @Override
    public Optional<PutawayTrip> findOpenByOperator(UUID operatorId) {
        return trips.findFirstByOperatorIdAndStatusIn(operatorId, OPEN).map(this::toDomain);
    }

    @Override
    public Optional<UUID> findOpenTripCarrying(UUID putawayTaskId) {
        @SuppressWarnings("unchecked")
        List<UUID> found = entityManager.createNativeQuery("""
                        select i.trip_id from warehouse.putaway_trip_item i
                         where i.putaway_task_id = :task and i.status = 'ON_CART'""")
                .setParameter("task", putawayTaskId).getResultList();
        return found.stream().findFirst();
    }

    @Override
    public List<Hold> findHolds(UUID warehouseId, UUID exceptTripId) {
        // A trip that is not this one: the all-zero id never names a trip, so "every trip" needs no second query.
        UUID except = exceptTripId == null ? new UUID(0L, 0L) : exceptTripId;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select i.trip_id, i.putaway_task_id, k.stock_package_id, i.assigned_location_id
                          from warehouse.putaway_trip_item i
                          join warehouse.putaway_trip t on t.id = i.trip_id
                          join warehouse.putaway_task k on k.id = i.putaway_task_id
                         where t.warehouse_id = :warehouse and t.status = 'IN_PROGRESS' and t.id <> :except
                           and i.status = 'ON_CART' and i.assigned_location_id is not null
                           and i.hold_released_at is null
                         order by i.id""")
                .setParameter("warehouse", warehouseId).setParameter("except", except).getResultList();
        return rows.stream().map(row -> new Hold((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3]))
                .toList();
    }

    /**
     * One statement, and the version of every row it touches moves: a trip saved from a copy read before this ran
     * fails its optimistic check instead of writing the hold back.
     */
    @Override
    public int releaseHoldsOfTripsIdleSince(Instant idleSince, Instant now) {
        return entityManager.createNativeQuery("""
                        update warehouse.putaway_trip_item i
                           set hold_released_at = :now, version = i.version + 1, last_modified_at = :now
                          from warehouse.putaway_trip t
                         where t.id = i.trip_id and t.status = 'IN_PROGRESS' and t.last_activity_at < :idleSince
                           and i.status = 'ON_CART' and i.assigned_location_id is not null
                           and i.hold_released_at is null""")
                .setParameter("now", now).setParameter("idleSince", idleSince)
                .executeUpdate();
    }

    /** Load-and-copy for existing rows, so versions and audit columns survive; new rows are inserted. */
    @Override
    public PutawayTrip save(PutawayTrip trip) {
        PutawayTripJpaEntity entity = trips.findById(trip.id()).orElseGet(() -> new PutawayTripJpaEntity(trip.id(),
                trip.warehouseId(), trip.operatorId(), trip.status(), trip.lastActivityAt()));
        entity.apply(trip.status(), trip.startLocationId(), trip.startedAt(), trip.lastActivityAt(), trip.closedAt(),
                trip.closedBy());
        PutawayTripJpaEntity savedTrip = trips.saveAndFlush(entity);

        Map<UUID, PutawayTripItemJpaEntity> known = items.findByTripIdOrderByIdAsc(trip.id()).stream()
                .collect(Collectors.toMap(PutawayTripItemJpaEntity::getId, Function.identity()));
        List<PutawayTripItemJpaEntity> rows = trip.items().stream().map(item -> {
            PutawayTripItemJpaEntity row = known.containsKey(item.id()) ? known.get(item.id())
                    : new PutawayTripItemJpaEntity(item.id(), trip.id(), item.taskId());
            row.apply(item.status(), item.assignedLocationId(), item.stopSequence(), item.holdReleasedAt(),
                    item.loadedAt(), item.placedAt());
            return row;
        }).toList();
        return toDomain(savedTrip, items.saveAllAndFlush(rows));
    }

    private PutawayTrip toDomain(PutawayTripJpaEntity e) {
        return toDomain(e, items.findByTripIdOrderByIdAsc(e.getId()));
    }

    private static PutawayTrip toDomain(PutawayTripJpaEntity e, List<PutawayTripItemJpaEntity> cartons) {
        return new PutawayTrip(e.getId(), e.getWarehouseId(), e.getOperatorId(), e.getStatus(),
                e.getStartLocationId(), e.getStartedAt(), e.getLastActivityAt(), e.getClosedAt(), e.getClosedBy(),
                cartons.stream().sorted(java.util.Comparator.comparing(PutawayTripItemJpaEntity::getId))
                        .map(c -> new PutawayTrip.Item(c.getId(), c.getPutawayTaskId(), c.getStatus(),
                                c.getAssignedLocationId(), c.getStopSequence(), c.getHoldReleasedAt(),
                                c.getLoadedAt(), c.getPlacedAt())).toList());
    }
}

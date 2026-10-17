package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.SkuReservation;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.entity.ReservationJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter for {@link SkuReservationRepository}. The locking reads lock the rows with a plain
 * {@code SELECT ... FOR UPDATE} first and then refresh the entities, for the reason
 * {@code StockItemRepositoryAdapter#findByIdForUpdate} gives: a locking JPQL query hands back whatever copy the
 * persistence context already holds, and the decision taken under the lock would run on pre-lock state.
 */
@Repository
class SkuReservationRepositoryAdapter implements SkuReservationRepository {

    private final SkuReservationJpaRepository jpa;
    private final EntityManager entityManager;

    SkuReservationRepositoryAdapter(SkuReservationJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public boolean exists(ReservationId reservationId) {
        return jpa.existsSoft(reservationId.value());
    }

    @Override
    public Optional<SkuReservation> findById(ReservationId reservationId) {
        return jpa.findSoft(List.of(reservationId.value())).stream().findFirst()
                .map(SkuReservationRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<SkuReservation> findByIdForUpdate(ReservationId reservationId) {
        return lock("select id from inventory.stock_reservation where id = :key and stock_item_id is null for update",
                reservationId.value()).stream().findFirst();
    }

    @Override
    public List<SkuReservation> findHeldForRequest(UUID rootRequestId) {
        return jpa.findHeldForRequest(rootRequestId).stream().map(SkuReservationRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<SkuReservation> findHeldForOrderForUpdate(UUID orderId) {
        return lock("""
                select id from inventory.stock_reservation
                 where order_id = :key and stock_item_id is null and status = 'HELD'
                 order by sku, id for update
                """, orderId).stream().filter(r -> r.status().isActive()).toList();
    }

    @Override
    public List<SkuReservation> findHeldForOrder(UUID orderId) {
        return jpa.findHeldForOrder(orderId).stream().map(SkuReservationRepositoryAdapter::toDomain).toList();
    }

    /** {@code SKIP LOCKED}: a reservation a checkout or a release is busy with is swept on the next run. */
    @Override
    public List<SkuReservation> findExpiredForUpdate(Instant cutoff, int limit) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("""
                        select id from inventory.stock_reservation
                         where stock_item_id is null and status = 'HELD' and expires_at < :cutoff
                         order by expires_at, id limit :limit for update skip locked
                        """)
                .setParameter("cutoff", cutoff).setParameter("limit", limit).getResultList();
        return refreshed(ids);
    }

    @Override
    public Map<String, Long> heldBySku(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> held = new HashMap<>();
        jpa.heldBySku(skus).forEach(row -> held.put(row.getSku(), row.getQuantity()));
        return held;
    }

    @Override
    public List<SkuReservation> findAll(Collection<UUID> reservationIds) {
        return reservationIds.isEmpty() ? List.of()
                : jpa.findSoft(reservationIds).stream().map(SkuReservationRepositoryAdapter::toDomain).toList();
    }

    @Override
    public SkuReservation save(SkuReservation reservation) {
        Optional<ReservationJpaEntity> existing = jpa.findById(reservation.id().value());
        if (existing.isPresent()) {
            ReservationJpaEntity managed = existing.get();
            if (managed.getStockItemOrNull() != null) {
                throw new IllegalStateException("Reservation %s is on a stock item".formatted(reservation.id()));
            }
            managed.apply(reservation.expiresAt(), reservation.status(), reservation.releaseReason(),
                    reservation.closedAt());
            return toDomain(jpa.save(managed));
        }
        return toDomain(jpa.save(new ReservationJpaEntity(reservation.id().value(), reservation.sku().code(),
                reservation.orderId(), null, reservation.rootRequestId(), reservation.requestId(),
                reservation.quantity().value(), reservation.reservedAt(), reservation.expiresAt(),
                reservation.status(), reservation.releaseReason(), reservation.closedAt(), null)));
    }

    private List<SkuReservation> lock(String sql, UUID key) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery(sql).setParameter("key", key).getResultList();
        return refreshed(ids);
    }

    private List<SkuReservation> refreshed(List<UUID> ids) {
        return ids.stream().map(id -> entityManager.find(ReservationJpaEntity.class, id))
                .filter(Objects::nonNull)
                .map(entity -> {
                    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
                    return toDomain(entity);
                })
                .toList();
    }

    static SkuReservation toDomain(ReservationJpaEntity e) {
        return new SkuReservation(new ReservationId(e.getId()), new Sku(e.getSku()), e.getOrderId(),
                e.getRootRequestId(), e.getRequestId(), Quantity.of(e.getQuantity()), e.getReservedAt(),
                e.getExpiresAt(), e.getStatus(), e.getReleaseReason(), e.getClosedAt());
    }
}

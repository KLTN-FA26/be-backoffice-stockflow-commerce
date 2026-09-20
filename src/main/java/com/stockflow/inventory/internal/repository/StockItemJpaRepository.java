package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.StockItemJpaEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository. Package-private and never injected outside this package — the rest of
 * the module talks to {@code StockItemRepository}, the domain port.
 *
 * <p>There is deliberately no {@code @Lock(PESSIMISTIC_WRITE)} query here. The lock is taken in
 * {@code StockItemRepositoryAdapter} with {@code EntityManager.refresh}, because a locking query
 * alone returns whatever copy of the entity is already in the persistence context and would leave
 * the availability re-check running on pre-lock data. Two ways of taking the same lock would
 * guarantee that one of them eventually gets used by accident.</p>
 *
 * <p>Every query is explicit JPQL rather than a derived method name. Derived names get unreadable
 * past two conditions ({@code findByStatusAndReservationsStatusAndReservationsExpiresAtBefore}),
 * and cannot express the fetch joins and lock modes these need.</p>
 */
interface StockItemJpaRepository extends JpaRepository<StockItemJpaEntity, UUID> {

    /**
     * Load one aggregate with its children in a single query.
     *
     * <p>Without the fetch join, reading N stock items triggers N extra SELECTs for their
     * reservations — the N+1 problem, invisible in a demo with three rows and fatal at 50 000.</p>
     */
    @Query("""
            select distinct s from StockItemJpaEntity s
            left join fetch s.reservations
            where s.id = :id
            """)
    Optional<StockItemJpaEntity> findByIdWithReservations(@Param("id") UUID id);

    /**
     * Sellable stock for one SKU, aggregates and all.
     *
     * <p>The query side: the ATP figure and the per-location breakdown are both computed from the
     * reservations, so the children have to come with it. Never called before taking a lock — that
     * path uses {@link #findAvailabilityBySku} precisely so nothing lands in the persistence
     * context.</p>
     */
    @Query("""
            select distinct s from StockItemJpaEntity s
            left join fetch s.reservations
            where s.sku = :sku
              and s.status = com.stockflow.inventory.internal.domain.StockStatus.AVAILABLE
            """)
    List<StockItemJpaEntity> findAvailableBySku(@Param("sku") String sku);

    /**
     * Projection for the allocation planner.
     *
     * <p>A constructor expression, so Hibernate builds {@code AvailabilityRow} objects straight
     * from the result set and puts NOTHING in the persistence context. That is the point: entities
     * loaded here would shadow the {@code SELECT ... FOR UPDATE} that follows, and the availability
     * re-check under the lock would run against pre-lock data.</p>
     *
     * <p>{@code reserved} is read from the denormalised column rather than aggregated from the
     * child table, which keeps this a single-table scan on the partial index.</p>
     */
    @Query("""
            select new com.stockflow.inventory.internal.repository.AvailabilityRow(
                s.id, s.locationCode, s.lotNumber, s.expiryDate, s.status, s.onHand, s.reserved)
            from StockItemJpaEntity s
            where s.sku = :sku
              and s.status = com.stockflow.inventory.internal.domain.StockStatus.AVAILABLE
              and s.onHand > s.reserved
            """)
    List<AvailabilityRow> findAvailabilityBySku(@Param("sku") String sku);

    /** Subtotals for the warehouse roll-up; every status, because on-hand counts them all. */
    @Query("""
            select new com.stockflow.inventory.internal.repository.StockLevelRow(
                s.locationCode, s.status, sum(s.onHand), sum(s.reserved))
            from StockItemJpaEntity s
            where s.sku = :sku
            group by s.locationCode, s.status
            """)
    List<StockLevelRow> findLevelRowsBySku(@Param("sku") String sku);

    @Query("""
            select distinct s from StockItemJpaEntity s
            left join fetch s.reservations
            where s.sku = :sku and s.locationCode = :locationCode
            """)
    List<StockItemJpaEntity> findBySkuAndLocation(@Param("sku") String sku,
                                                  @Param("locationCode") String locationCode);

    /** Ids only, so the sweeper can page through the backlog before locking anything. */
    @Query("""
            select distinct r.stockItem.id from ReservationJpaEntity r
            where r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
              and r.expiresAt < :cutoff
            """)
    List<UUID> findStockItemIdsWithExpiredReservations(@Param("cutoff") Instant cutoff,
                                                       org.springframework.data.domain.Pageable page);

    @Query("""
            select distinct r.stockItem.id from ReservationJpaEntity r
            where r.rootRequestId = :rootRequestId
              and r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
            """)
    List<UUID> findStockItemIdsForRequest(@Param("rootRequestId") UUID rootRequestId);

    @Query("""
            select distinct r.stockItem.id from ReservationJpaEntity r
            where r.orderId = :orderId
              and r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
            """)
    List<UUID> findStockItemIdsHoldingOrder(@Param("orderId") UUID orderId);

    @Query("select r.stockItem.id from ReservationJpaEntity r where r.id = :reservationId")
    Optional<UUID> findStockItemIdByReservationId(@Param("reservationId") UUID reservationId);
}

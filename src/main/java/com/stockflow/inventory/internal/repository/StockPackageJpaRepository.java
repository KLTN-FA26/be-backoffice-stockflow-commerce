package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.StockPackageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository of the carton. Package-private and never injected outside this package — the rest of the
 * module talks to {@code StockPackageRepository}, the domain port.
 *
 * <p>There is deliberately no {@code @Lock(PESSIMISTIC_WRITE)} query here. The lock is taken in
 * {@code StockPackageRepositoryAdapter} with a native {@code FOR UPDATE} and {@code EntityManager.refresh}, because a
 * locking query alone returns whatever copy of the entity is already in the persistence context and would leave the
 * re-check under the lock running on pre-lock data. Two ways of taking the same lock would guarantee that one of them
 * eventually gets used by accident.</p>
 */
interface StockPackageJpaRepository extends JpaRepository<StockPackageJpaEntity, UUID>,
        JpaSpecificationExecutor<StockPackageJpaEntity> {

    Optional<StockPackageJpaEntity> findByLpn(String lpn);

    List<StockPackageJpaEntity> findByLpnIn(Collection<String> lpns);

    List<StockPackageJpaEntity> findByGoodsReceiptLineIdIn(Collection<UUID> goodsReceiptLineIds);

    /**
     * One carton with its allocations in a single query: the shape every write loads. Without the fetch join, locking
     * N cartons costs N extra SELECTs for their reservations.
     */
    @Query("""
            select distinct p from StockPackageJpaEntity p
            left join fetch p.reservations
            where p.id = :id
            """)
    Optional<StockPackageJpaEntity> findByIdWithReservations(@Param("id") UUID id);

    /** The carton an allocation is on. */
    @Query("select p.id from ReservationJpaEntity r join r.stockPackage p where r.id = :reservationId")
    Optional<UUID> findIdByReservationId(@Param("reservationId") UUID reservationId);

    @Query("""
            select distinct p.id from ReservationJpaEntity r join r.stockPackage p
            where r.rootRequestId = :rootRequestId
              and r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
            """)
    List<UUID> findIdsHoldingRequest(@Param("rootRequestId") UUID rootRequestId);

    /** As {@link #findIdsHoldingRequest}, whatever became of the allocations since: picked or released too. */
    @Query("""
            select distinct p.id from ReservationJpaEntity r join r.stockPackage p
            where r.rootRequestId = :rootRequestId
            """)
    List<UUID> findIdsEverReservedByRequest(@Param("rootRequestId") UUID rootRequestId);

    @Query("""
            select new com.stockflow.inventory.internal.domain.HeldStock(p.sku, p.locationCode,
                   r.cartons * r.unitsPerCarton)
            from ReservationJpaEntity r join r.stockPackage p
            where r.orderId = :orderId
              and r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
            order by r.sku, p.locationCode
            """)
    List<com.stockflow.inventory.internal.domain.HeldStock> findHeldForOrder(@Param("orderId") UUID orderId);

    /**
     * {@code wholeCarton}: the allocation is of a carton in stock - it always is the whole of it (ADR-0011), so the
     * flag only says the carton is still there to pick.
     */
    @Query("""
            select new com.stockflow.inventory.internal.domain.HoldDetail(r.id, r.sku, p.locationCode, p.lotNumber,
                   r.cartons * r.unitsPerCarton, r.status, p.lpn,
                   case when p.status = com.stockflow.inventory.internal.domain.StockPackageStatus.IN_STOCK
                        then true else false end)
            from ReservationJpaEntity r join r.stockPackage p
            where r.id in :ids
            """)
    List<com.stockflow.inventory.internal.domain.HoldDetail> findHolds(@Param("ids") Collection<UUID> ids);
}

package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.ReservationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data over the SKU reservations in {@code inventory.stock_reservation}: every query is restricted to
 * {@code stockItem is null}, so a hold on a stock item — which only its {@code StockItem} may change — is never
 * returned here. Package-private; the module uses {@code SkuReservationRepository}.
 */
interface SkuReservationJpaRepository extends JpaRepository<ReservationJpaEntity, UUID> {

    interface HeldTotal {
        String getSku();
        Long getQuantity();
    }

    @Query("select count(r) > 0 from ReservationJpaEntity r where r.id = :id and r.stockItem is null")
    boolean existsSoft(@Param("id") UUID id);

    @Query("""
            select r from ReservationJpaEntity r
            where r.stockItem is null and r.rootRequestId = :rootRequestId
              and r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
            order by r.sku, r.id
            """)
    List<ReservationJpaEntity> findHeldForRequest(@Param("rootRequestId") UUID rootRequestId);

    @Query("""
            select r from ReservationJpaEntity r
            where r.stockItem is null and r.orderId = :orderId
              and r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
            order by r.sku, r.id
            """)
    List<ReservationJpaEntity> findHeldForOrder(@Param("orderId") UUID orderId);

    @Query("""
            select r.sku as sku, sum(r.quantity) as quantity from ReservationJpaEntity r
            where r.stockItem is null and r.sku in :skus
              and r.status = com.stockflow.inventory.internal.domain.ReservationStatus.HELD
            group by r.sku
            """)
    List<HeldTotal> heldBySku(@Param("skus") Collection<String> skus);

    @Query("select r from ReservationJpaEntity r where r.stockItem is null and r.id in :ids")
    List<ReservationJpaEntity> findSoft(@Param("ids") Collection<UUID> ids);
}

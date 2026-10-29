package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.entity.StockDamageReportJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Package-private Spring Data over {@code inventory.stock_damage_report}; the module uses the port. */
interface StockDamageReportJpaRepository extends BaseJpaRepository<StockDamageReportJpaEntity> {

    Optional<StockDamageReportJpaEntity> findByRequestId(UUID requestId);

    @Query("""
            select coalesce(sum(r.quantity), 0) from StockDamageReportJpaEntity r
             where r.reservationId = :reservationId
               and r.origin = com.stockflow.inventory.internal.domain.StockDamageReport.Origin.PACKING""")
    long damagedAfterPick(@Param("reservationId") UUID reservationId);

    /** Units decided so far, per report, for one page of a list: one query, whatever the page size. */
    @Query("""
            select d.report.id as reportId, sum(d.quantity) as decided
              from StockDamageDispositionJpaEntity d
             where d.report.id in :reportIds
             group by d.report.id""")
    List<DecidedUnits> decidedOf(@Param("reportIds") Collection<UUID> reportIds);

    /** Units suppliers refused, per report: they wait for a decision again. */
    @Query("""
            select s.report.id as reportId, sum(s.quantity) as decided
              from StockDamageSupplierShipmentJpaEntity s
             where s.report.id in :reportIds and s.outcome = 'REFUSED'
             group by s.report.id""")
    List<DecidedUnits> refusedOf(@Param("reportIds") Collection<UUID> reportIds);

    /** Units decided for the supplier and neither handed over nor refused yet, per report. */
    @Query("""
            select d.report.id as reportId,
                   sum(d.quantity) - coalesce((select sum(s.quantity) from StockDamageSupplierShipmentJpaEntity s
                                                where s.report.id = d.report.id), 0) as decided
              from StockDamageDispositionJpaEntity d
             where d.report.id in :reportIds
               and d.type = com.stockflow.inventory.internal.domain.DamageDisposition.RETURN_TO_SUPPLIER
             group by d.report.id""")
    List<DecidedUnits> awaitingSupplierOf(@Param("reportIds") Collection<UUID> reportIds);

    /** Whether {@code reservationId} is the impaired allocation a damage report left on its damaged units. */
    boolean existsByImpairedReservationId(UUID reservationId);

    /** Projection of {@link #decidedOf}. */
    interface DecidedUnits {
        UUID getReportId();

        Long getDecided();
    }
}

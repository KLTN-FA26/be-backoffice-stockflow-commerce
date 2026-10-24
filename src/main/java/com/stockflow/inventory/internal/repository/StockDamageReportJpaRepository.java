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

    /** Units decided so far, per report, for one page of a list: one query, whatever the page size. */
    @Query("""
            select d.report.id as reportId, sum(d.quantity) as decided
              from StockDamageDispositionJpaEntity d
             where d.report.id in :reportIds
             group by d.report.id""")
    List<DecidedUnits> decidedOf(@Param("reportIds") Collection<UUID> reportIds);

    /** Projection of {@link #decidedOf}. */
    interface DecidedUnits {
        UUID getReportId();

        Long getDecided();
    }
}

package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.entity.GoodsReceiptJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface GoodsReceiptJpaRepository extends BaseJpaRepository<GoodsReceiptJpaEntity> {

    /** Lines fetched with the header. */
    @Query("select distinct r from GoodsReceiptJpaEntity r left join fetch r.lines where r.id = :id")
    Optional<GoodsReceiptJpaEntity> findByIdWithLines(@Param("id") UUID id);

    /** 4A-5 (03 BR-02): units of production order lines on the other receipts that are not cancelled. */
    @Query("""
            select l.productionOrderLineId, sum(l.receivedQty) from GoodsReceiptLineJpaEntity l join l.receipt r
             where l.productionOrderLineId in :lineIds and r.status <> :cancelled and r.id <> :excluding
             group by l.productionOrderLineId""")
    List<Object[]> sumByProductionOrderLineExcluding(@Param("lineIds") Collection<UUID> productionOrderLineIds,
                                                     @Param("excluding") UUID excluding,
                                                     @Param("cancelled") GoodsReceiptStatus cancelled);
}

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

    /** Lines fetched with the header; inspections follow per line in batches ({@code @BatchSize}). */
    @Query("select distinct r from GoodsReceiptJpaEntity r left join fetch r.lines where r.id = :id")
    Optional<GoodsReceiptJpaEntity> findByIdWithLines(@Param("id") UUID id);

    @Query("""
            select l.poLineId, sum(l.receivedQty) from GoodsReceiptLineJpaEntity l join l.receipt r
             where l.poLineId in :poLineIds and r.status <> :cancelled and r.id <> :excluding
             group by l.poLineId""")
    List<Object[]> sumByPoLineExcluding(@Param("poLineIds") Collection<UUID> poLineIds,
                                        @Param("excluding") UUID excluding,
                                        @Param("cancelled") GoodsReceiptStatus cancelled);

    @Query("""
            select l.poLineId, sum(l.receivedQty) from GoodsReceiptLineJpaEntity l join l.receipt r
             where r.purchaseOrderId = :po and r.status not in :notCounted
             group by l.poLineId""")
    List<Object[]> sumByPoLineOfOrder(@Param("po") UUID purchaseOrderId,
                                      @Param("notCounted") Collection<GoodsReceiptStatus> notCounted);
}

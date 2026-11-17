package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.entity.ReturnReceiptJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReturnReceiptJpaRepository extends BaseJpaRepository<ReturnReceiptJpaEntity> {

    /** Cartons fetched with the header. */
    @Query("select distinct r from ReturnReceiptJpaEntity r left join fetch r.cartons where r.id = :id")
    Optional<ReturnReceiptJpaEntity> findByIdWithCartons(@Param("id") UUID id);

    @Query("select r.id from ReturnReceiptJpaEntity r where r.returnShipmentId = :id and r.status <> :cancelled")
    List<UUID> findLiveByShipment(@Param("id") UUID shipmentId, @Param("cancelled") GoodsReceiptStatus cancelled);

    @Query("select r.id from ReturnReceiptJpaEntity r where r.returnRequestId = :id and r.status <> :cancelled")
    List<UUID> findLiveByReturnRequest(@Param("id") UUID returnRequestId,
                                       @Param("cancelled") GoodsReceiptStatus cancelled);

    /** How many cartons each receipt lists, for a page of the list: one query, not one per row. */
    @Query("""
            select c.receipt.id, count(c) from ReturnReceiptCartonJpaEntity c
             where c.receipt.id in :ids group by c.receipt.id""")
    List<Object[]> countCartons(@Param("ids") Collection<UUID> receiptIds);
}

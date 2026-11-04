package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.domain.PutawayStatus;
import com.stockflow.warehouse.internal.entity.PutawayTaskJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link PutawayTaskJpaEntity}. */
interface PutawayTaskJpaRepository extends BaseJpaRepository<PutawayTaskJpaEntity> {

    Optional<PutawayTaskJpaEntity> findFirstByStockPackageIdAndStatusIn(UUID stockPackageId,
                                                                      Collection<PutawayStatus> statuses);

    Optional<PutawayTaskJpaEntity> findFirstByGoodsReceiptLineIdAndStockPackageIdIsNull(UUID goodsReceiptLineId);

    List<PutawayTaskJpaEntity> findByGoodsReceiptIdOrderByIdAsc(UUID goodsReceiptId);

    List<PutawayTaskJpaEntity> findByStatusInOrderByIdAsc(Collection<PutawayStatus> statuses);

    @Query("""
            select t.goodsReceiptLineId, sum(t.quantity) from PutawayTaskJpaEntity t
            where t.goodsReceiptId = :receiptId and t.status = com.stockflow.warehouse.internal.domain.PutawayStatus.DONE
            group by t.goodsReceiptLineId
            """)
    List<Object[]> sumDoneByReceiptLine(@Param("receiptId") UUID receiptId);
}

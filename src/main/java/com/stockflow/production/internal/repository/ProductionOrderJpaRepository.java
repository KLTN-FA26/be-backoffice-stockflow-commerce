package com.stockflow.production.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.entity.ProductionOrderJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProductionOrderJpaRepository extends BaseJpaRepository<ProductionOrderJpaEntity> {

    /** {@code uk_production_order_sample_request}: at most one. */
    @Query("""
            select p from ProductionOrderJpaEntity p
             where p.type = com.stockflow.production.internal.domain.ProductionOrder.Type.SAMPLE
               and p.sampleRequestId = :id""")
    Optional<ProductionOrderJpaEntity> findForSample(@Param("id") UUID sampleRequestId);

    /** {@code uk_production_order_line}: one root per line; subcontracted children are not the source's order. */
    @Query("""
            select p from ProductionOrderJpaEntity p
             where p.type = com.stockflow.production.internal.domain.ProductionOrder.Type.ORDER
               and p.salesOrderLineId = :id and p.parentId is null""")
    Optional<ProductionOrderJpaEntity> findRootForLine(@Param("id") UUID salesOrderLineId);

    @Query("""
            select count(p) > 0 from ProductionOrderJpaEntity p
             where p.type = com.stockflow.production.internal.domain.ProductionOrder.Type.ORDER
               and p.designSnapshotId = :design and p.salesOrderId <> :order
               and p.status = com.stockflow.production.internal.domain.ProductionOrder.Status.COMPLETED""")
    boolean existsCompletedForAnotherOrder(@Param("design") UUID designSnapshotId, @Param("order") UUID salesOrderId);

    @Query("select p.id from ProductionOrderJpaEntity p where p.purchaseOrderId = :po")
    Optional<UUID> findIdByPurchaseOrder(@Param("po") UUID purchaseOrderId);

    @Query("select p.id from ProductionOrderJpaEntity p where p.salesOrderId = :order order by p.id")
    List<UUID> findIdsBySalesOrder(@Param("order") UUID salesOrderId);

    List<ProductionOrderJpaEntity> findByTypeAndSampleRequestIdIn(ProductionOrder.Type type,
                                                                  Collection<UUID> sampleRequestIds);
}

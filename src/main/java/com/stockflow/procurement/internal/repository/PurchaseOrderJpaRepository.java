package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PurchaseOrderJpaRepository extends BaseJpaRepository<PurchaseOrderJpaEntity> {

    @EntityGraph(attributePaths = "lines")
    Optional<PurchaseOrderJpaEntity> findWithLinesById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PurchaseOrderJpaEntity po where po.id = :id")
    Optional<PurchaseOrderJpaEntity> findByIdForUpdate(UUID id);

    @EntityGraph(attributePaths = "lines")
    List<PurchaseOrderJpaEntity> findBySupplierIdAndExpectedAtAndStatusNotIn(
            UUID supplierId, LocalDate expectedAt, Collection<PurchaseOrderStatus> excludedStatuses);

    /**
     * The live SUBCONTRACT order of a production order. A single result, not "first": the partial
     * index {@code uk_purchase_orders_production_order} allows one, and a limit over the lines fetch
     * join would be paged in memory (refused by {@code fail_on_pagination_over_collection_fetch}).
     */
    @EntityGraph(attributePaths = "lines")
    Optional<PurchaseOrderJpaEntity> findByTypeAndProductionOrderIdAndStatusNot(
            PurchaseOrder.Type type, UUID productionOrderId, PurchaseOrderStatus status);

    @Query("select po.status, count(po) from PurchaseOrderJpaEntity po group by po.status")
    List<Object[]> countByStatus();
}

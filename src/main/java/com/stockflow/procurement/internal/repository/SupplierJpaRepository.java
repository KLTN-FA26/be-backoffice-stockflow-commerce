package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Supplier writes and PO creation share this lock to serialize deactivation against new orders. */
interface SupplierJpaRepository extends BaseJpaRepository<SupplierJpaEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupplierJpaEntity s where s.id = :id")
    Optional<SupplierJpaEntity> findByIdForUpdate(UUID id);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    boolean existsByTaxCode(String taxCode);

    boolean existsByTaxCodeAndIdNot(String taxCode, UUID id);

    @Query(
            """
            select count(po) > 0 from PurchaseOrderJpaEntity po
            where po.supplierId = :supplierId and po.status in :statuses
            """)
    boolean hasPurchaseOrdersInStatuses(UUID supplierId, Collection<PurchaseOrderStatus> statuses);

    @Query(
            value =
                    """
WITH receipts AS (
    SELECT po.id, MAX(gr.received_at) received_at
    FROM procurement.purchase_order po
    LEFT JOIN procurement.goods_receipt gr ON gr.purchase_order_id = po.id AND gr.status = 'COMPLETED'
    WHERE po.supplier_id = :supplierId
    GROUP BY po.id
), quality AS (
    SELECT COALESCE(SUM(q.quantity_passed), 0) passed,
           COALESCE(SUM(q.quantity_failed), 0) failed
    FROM procurement.goods_receipt gr
    JOIN procurement.purchase_order po ON po.id = gr.purchase_order_id
    JOIN procurement.qc_result q ON q.goods_receipt_id = gr.id
    WHERE po.supplier_id = :supplierId AND gr.status = 'COMPLETED' AND q.inspected_at IS NOT NULL
)
SELECT COUNT(po.id) totalPurchaseOrders,
       COUNT(po.id) FILTER (WHERE po.status = 'CLOSED') fulfilledPurchaseOrders,
       COUNT(po.id) FILTER (WHERE po.status = 'CLOSED' AND po.expected_at IS NOT NULL
           AND (COALESCE(po.receipt_completed_at, r.received_at) AT TIME ZONE :businessZone)::date <= po.expected_at) onTimeOrders,
       COUNT(po.id) FILTER (WHERE po.status = 'CLOSED' AND po.expected_at IS NOT NULL
           AND (COALESCE(po.receipt_completed_at, r.received_at) AT TIME ZONE :businessZone)::date > po.expected_at) lateOrders,
       AVG(EXTRACT(EPOCH FROM (COALESCE(po.receipt_completed_at, r.received_at) - po.sent_at)) / 86400.0)
           FILTER (WHERE po.status = 'CLOSED' AND po.sent_at IS NOT NULL
               AND COALESCE(po.receipt_completed_at, r.received_at) >= po.sent_at) averageLeadTimeDays,
       (SELECT passed FROM quality) acceptedQuantity, (SELECT failed FROM quality) rejectedQuantity
FROM procurement.purchase_order po
LEFT JOIN receipts r ON r.id = po.id
WHERE po.supplier_id = :supplierId
""",
            nativeQuery = true)
    SupplierPerformanceProjection performance(UUID supplierId, String businessZone);
}

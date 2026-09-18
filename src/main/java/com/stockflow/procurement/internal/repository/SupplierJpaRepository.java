package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link SupplierJpaEntity}. STARTER STUB. */
public interface SupplierJpaRepository extends BaseJpaRepository<SupplierJpaEntity> {
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, java.util.UUID id);
    boolean existsByTaxCode(String taxCode);
    boolean existsByTaxCodeAndIdNot(String taxCode, java.util.UUID id);

    @org.springframework.data.jpa.repository.Query("""
            select count(po) > 0 from PurchaseOrderJpaEntity po
            where po.supplierId = :supplierId and po.status in :statuses
            """)
    boolean hasPurchaseOrdersInStatuses(java.util.UUID supplierId,
                                        java.util.Collection<com.stockflow.procurement.internal.domain.PurchaseOrderStatus> statuses);

    @org.springframework.data.jpa.repository.Query(value = """
            WITH receipts AS (
                SELECT po.id, MIN(gr.received_at) received_at
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
                WHERE po.supplier_id = :supplierId
            )
            SELECT COUNT(po.id) totalPurchaseOrders,
                   COUNT(po.id) FILTER (WHERE po.status IN ('CLOSED','CLOSED_SHORT')) fulfilledPurchaseOrders,
                   COUNT(po.id) FILTER (WHERE po.status IN ('CLOSED','CLOSED_SHORT') AND po.expected_at IS NOT NULL
                       AND COALESCE(r.received_at, po.last_modified_at)::date <= po.expected_at) onTimeOrders,
                   COUNT(po.id) FILTER (WHERE po.status IN ('CLOSED','CLOSED_SHORT') AND po.expected_at IS NOT NULL
                       AND COALESCE(r.received_at, po.last_modified_at)::date > po.expected_at) lateOrders,
                   COALESCE(AVG(EXTRACT(DAY FROM (COALESCE(r.received_at, po.last_modified_at) - po.sent_at)))
                       FILTER (WHERE po.status IN ('CLOSED','CLOSED_SHORT') AND po.sent_at IS NOT NULL), 0) averageLeadTimeDays,
                   q.passed acceptedQuantity, q.failed rejectedQuantity
            FROM procurement.purchase_order po
            LEFT JOIN receipts r ON r.id = po.id
            CROSS JOIN quality q
            WHERE po.supplier_id = :supplierId
            GROUP BY q.passed, q.failed
            """, nativeQuery = true)
    SupplierPerformanceProjection performance(java.util.UUID supplierId);
}

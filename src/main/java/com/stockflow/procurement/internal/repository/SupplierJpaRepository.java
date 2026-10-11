package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface SupplierJpaRepository extends BaseJpaRepository<SupplierJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupplierJpaEntity s where s.id = :id")
    Optional<SupplierJpaEntity> findByIdForUpdate(UUID id);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    boolean existsByTaxCode(String taxCode);

    boolean existsByTaxCodeAndIdNot(String taxCode, UUID id);

    /** Open = still expecting goods or still on its way to the supplier: not received, closed or cancelled. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM procurement.purchase_orders
                            WHERE supplier_id = :supplierId
                              AND status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED'))
            """, nativeQuery = true)
    boolean hasOpenPurchaseOrders(UUID supplierId);

    /**
     * On-time delivery, lead time and quality of one supplier, from its purchase orders and their goods
     * receipts. A PO is fulfilled once every line is received ({@code RECEIVED}, or {@code CLOSED} the
     * normal way); it was delivered on the day its last receipt was confirmed, counted from the day it
     * was confirmed to the supplier (sent). Quality counts the QC decisions: accepted against rejected;
     * a quarantine is not yet a verdict.
     */
    @Query(value = """
            WITH receipts AS (
                SELECT po.id, MAX(gr.confirmed_at) received_at
                  FROM procurement.purchase_orders po
                  LEFT JOIN procurement.goods_receipts gr
                         ON gr.po_id = po.id AND gr.confirmed_at IS NOT NULL AND gr.status <> 'CANCELLED'
                 WHERE po.supplier_id = :supplierId
                 GROUP BY po.id
            ), quality AS (
                SELECT COALESCE(SUM(q.quantity) FILTER (WHERE q.outcome = 'ACCEPTED'), 0) passed,
                       COALESCE(SUM(q.quantity) FILTER (WHERE q.outcome = 'REJECTED'), 0) failed
                  FROM procurement.qc_inspections q
                  JOIN procurement.goods_receipt_lines l ON l.id = q.receipt_line_id
                  JOIN procurement.goods_receipts gr ON gr.id = l.receipt_id
                  JOIN procurement.purchase_orders po ON po.id = gr.po_id
                 WHERE po.supplier_id = :supplierId
            ), orders AS (
                SELECT po.*, r.received_at,
                       (po.status = 'RECEIVED' OR (po.status = 'CLOSED' AND po.close_kind = 'NORMAL')) fulfilled
                  FROM procurement.purchase_orders po
                  LEFT JOIN receipts r ON r.id = po.id
                 WHERE po.supplier_id = :supplierId
            )
            SELECT COUNT(*) totalPurchaseOrders,
                   COUNT(*) FILTER (WHERE fulfilled) fulfilledPurchaseOrders,
                   COUNT(*) FILTER (WHERE fulfilled AND expected_date IS NOT NULL AND received_at IS NOT NULL
                       AND (received_at AT TIME ZONE :businessZone)::date <= expected_date) onTimeOrders,
                   COUNT(*) FILTER (WHERE fulfilled AND expected_date IS NOT NULL AND received_at IS NOT NULL
                       AND (received_at AT TIME ZONE :businessZone)::date > expected_date) lateOrders,
                   AVG(EXTRACT(EPOCH FROM (received_at - confirmed_at)) / 86400.0)
                       FILTER (WHERE fulfilled AND confirmed_at IS NOT NULL AND received_at >= confirmed_at)
                       averageLeadTimeDays,
                   (SELECT passed FROM quality)::bigint acceptedQuantity,
                   (SELECT failed FROM quality)::bigint rejectedQuantity
              FROM orders
            """, nativeQuery = true)
    SupplierPerformanceProjection performance(UUID supplierId, String businessZone);
}

package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.entity.SupplierItemJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface SupplierItemJpaRepository extends BaseJpaRepository<SupplierItemJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from SupplierItemJpaEntity i where i.id = :id")
    Optional<SupplierItemJpaEntity> findByIdForUpdate(UUID id);

    Page<SupplierItemJpaEntity> findBySupplierId(UUID supplierId, Pageable pageable);

    /**
     * Open = still expecting goods or still on its way to the supplier: not received, closed or
     * cancelled. The same list as {@code SupplierJpaRepository.hasOpenPurchaseOrders}, narrowed to one
     * SKU.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1
                             FROM procurement.purchase_order_lines l
                             JOIN procurement.purchase_orders po ON po.id = l.po_id
                            WHERE po.supplier_id = :supplierId
                              AND l.inventory_item_id = :inventoryItemId
                              AND po.status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED'))
            """, nativeQuery = true)
    boolean usedByOpenPurchaseOrder(UUID supplierId, UUID inventoryItemId);
}

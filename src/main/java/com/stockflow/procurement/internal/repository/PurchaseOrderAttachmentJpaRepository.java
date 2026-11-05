package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.entity.PurchaseOrderAttachmentJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The documents kept with purchase orders. */
public interface PurchaseOrderAttachmentJpaRepository extends BaseJpaRepository<PurchaseOrderAttachmentJpaEntity> {

    List<PurchaseOrderAttachmentJpaEntity> findByPurchaseOrderIdOrderByIdAsc(UUID purchaseOrderId);

    Optional<PurchaseOrderAttachmentJpaEntity> findByIdAndPurchaseOrderId(UUID id, UUID purchaseOrderId);

    Optional<PurchaseOrderAttachmentJpaEntity> findByStorageKey(String storageKey);

    /** The warehouse of a purchase order, or empty when there is no such order. */
    @Query(value = "SELECT warehouse_id FROM procurement.purchase_orders WHERE id = :id", nativeQuery = true)
    Optional<UUID> warehouseOf(@Param("id") UUID purchaseOrderId);

    @Query("SELECT a.storageKey FROM PurchaseOrderAttachmentJpaEntity a WHERE a.storageKey IN :keys")
    List<String> storageKeysIn(@Param("keys") Collection<String> keys);
}

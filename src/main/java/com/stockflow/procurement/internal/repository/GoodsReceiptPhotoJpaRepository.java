package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptPhotoStage;
import com.stockflow.procurement.internal.entity.GoodsReceiptPhotoJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The photos of goods receipts, and the receipt facts a photo is checked against. */
public interface GoodsReceiptPhotoJpaRepository extends BaseJpaRepository<GoodsReceiptPhotoJpaEntity> {

    /** A receipt's status and warehouse. */
    interface ReceiptFacts {
        String getStatus();

        UUID getWarehouseId();
    }

    List<GoodsReceiptPhotoJpaEntity> findByReceiptIdOrderByIdAsc(UUID receiptId);

    Optional<GoodsReceiptPhotoJpaEntity> findByIdAndReceiptId(UUID id, UUID receiptId);

    Optional<GoodsReceiptPhotoJpaEntity> findByStorageKey(String storageKey);

    long countByReceiptIdAndStageAndReceiptLineId(UUID receiptId, GoodsReceiptPhotoStage stage, UUID receiptLineId);

    long countByReceiptIdAndStageAndReceiptLineIdIsNull(UUID receiptId, GoodsReceiptPhotoStage stage);

    @Query(value = "SELECT CAST(status AS text) AS status, warehouse_id AS warehouseId FROM procurement.goods_receipts WHERE id = :id",
            nativeQuery = true)
    Optional<ReceiptFacts> receiptFacts(@Param("id") UUID receiptId);

    /**
     * {@link #receiptFacts} with the receipt row locked until commit, as every status change of a receipt locks it
     * ({@code GoodsReceiptRepository.findByIdForUpdate}): a photo is kept only at the step it belongs to, and the
     * count of photos already kept cannot be overtaken by a parallel upload.
     */
    @Query(value = "SELECT CAST(status AS text) AS status, warehouse_id AS warehouseId FROM procurement.goods_receipts WHERE id = :id FOR UPDATE",
            nativeQuery = true)
    Optional<ReceiptFacts> lockReceipt(@Param("id") UUID receiptId);

    /** Whether a line of this receipt goes through QC; empty when it is not a line of this receipt. */
    @Query(value = "SELECT qc_required FROM procurement.goods_receipt_lines WHERE id = :line AND receipt_id = :receipt",
            nativeQuery = true)
    Optional<Boolean> qcRequiredOf(@Param("line") UUID receiptLineId, @Param("receipt") UUID receiptId);

    @Query("SELECT p.storageKey FROM GoodsReceiptPhotoJpaEntity p WHERE p.storageKey IN :keys")
    List<String> storageKeysIn(@Param("keys") Collection<String> keys);
}

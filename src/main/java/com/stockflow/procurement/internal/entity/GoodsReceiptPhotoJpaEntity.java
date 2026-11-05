package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.GoodsReceiptPhotoStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code procurement.goods_receipt_photos} (V20261106000100): a photo taken while goods were received or inspected.
 * Append-only ({@code tg_goods_receipt_photos_append_only}): every column is written once.
 */
@Entity
@Table(name = "goods_receipt_photos", schema = "procurement")
public class GoodsReceiptPhotoJpaEntity extends BaseEntity {

    @Column(name = "receipt_id", nullable = false, updatable = false)
    private UUID receiptId;

    @Column(name = "receipt_line_id", updatable = false)
    private UUID receiptLineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16, updatable = false)
    private GoodsReceiptPhotoStage stage;

    @Column(name = "storage_key", nullable = false, length = 512, updatable = false)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 255, updatable = false)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 32, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "checksum", nullable = false, length = 64, updatable = false)
    private String checksum;

    @Column(name = "taken_by", nullable = false, updatable = false)
    private UUID takenBy;

    protected GoodsReceiptPhotoJpaEntity() {
    }

    public GoodsReceiptPhotoJpaEntity(UUID id, UUID receiptId, UUID receiptLineId, GoodsReceiptPhotoStage stage,
                                      String storageKey, String originalName, String contentType, long sizeBytes,
                                      String checksum, UUID takenBy) {
        super(id);
        this.receiptId = receiptId;
        this.receiptLineId = receiptLineId;
        this.stage = stage;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.takenBy = takenBy;
    }

    public UUID getReceiptId() { return receiptId; }
    public UUID getReceiptLineId() { return receiptLineId; }
    public GoodsReceiptPhotoStage getStage() { return stage; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksum() { return checksum; }
    public UUID getTakenBy() { return takenBy; }
    /** When it was taken: the row's creation time. */
    public Instant getTakenAt() { return getCreatedAt(); }
}

package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.PurchaseOrderAttachmentCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code procurement.purchase_order_attachments} (V20260928002200): a document kept with a purchase order.
 *
 * <p>{@code file_url} holds the object's storage key, never a URL: a link is signed on demand and lives minutes. The
 * column predates the storage layer and keeps its name until the schema owner renames it. {@code file_type} is the
 * file's extension ({@code pdf}, {@code docx}, ...), which fits its 20 characters where a content type would not.</p>
 */
@Entity
@Table(name = "purchase_order_attachments", schema = "procurement")
public class PurchaseOrderAttachmentJpaEntity extends BaseEntity {

    @Column(name = "po_id", nullable = false, updatable = false)
    private UUID purchaseOrderId;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "file_url", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "file_type", length = 20, updatable = false)
    private String fileType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private PurchaseOrderAttachmentCategory category;

    @Column(name = "file_size", updatable = false)
    private Integer fileSize;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    protected PurchaseOrderAttachmentJpaEntity() {
    }

    public PurchaseOrderAttachmentJpaEntity(UUID id, UUID purchaseOrderId, String fileName, String storageKey,
                                            String fileType, PurchaseOrderAttachmentCategory category, long fileSize,
                                            UUID uploadedBy) {
        super(id);
        this.purchaseOrderId = purchaseOrderId;
        this.fileName = fileName;
        this.storageKey = storageKey;
        this.fileType = fileType;
        this.category = category;
        this.fileSize = Math.toIntExact(fileSize);
        this.uploadedBy = uploadedBy;
    }

    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public String getFileName() { return fileName; }
    public String getStorageKey() { return storageKey; }
    public String getFileType() { return fileType; }
    public PurchaseOrderAttachmentCategory getCategory() { return category; }
    public Integer getFileSize() { return fileSize; }
    public UUID getUploadedBy() { return uploadedBy; }
    /** When it was kept: the row's creation time. */
    public Instant getUploadedAt() { return getCreatedAt(); }
}

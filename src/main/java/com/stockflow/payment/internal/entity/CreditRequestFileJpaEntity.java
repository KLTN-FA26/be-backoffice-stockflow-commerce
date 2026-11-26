package com.stockflow.payment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A document kept with a credit request (table {@code payment.credit_request_file}): the business registration,
 * financial statements. In the {@code documents} category of the common storage; written once.
 */
@Entity
@Table(name = "credit_request_file", schema = "payment")
public class CreditRequestFileJpaEntity extends BaseEntity {

    @Column(name = "credit_request_id", nullable = false, updatable = false) private UUID creditRequestId;
    @Column(name = "storage_key", nullable = false, length = 512, updatable = false) private String storageKey;
    @Column(name = "file_name", nullable = false, length = 255, updatable = false) private String fileName;
    @Column(name = "content_type", nullable = false, length = 100, updatable = false) private String contentType;
    @Column(name = "size_bytes", nullable = false, updatable = false) private long sizeBytes;
    @Column(name = "uploaded_by", nullable = false, updatable = false) private UUID uploadedBy;
    @Column(name = "uploaded_at", nullable = false, updatable = false) private Instant uploadedAt;

    protected CreditRequestFileJpaEntity() {
    }

    public CreditRequestFileJpaEntity(UUID id, UUID creditRequestId, String storageKey, String fileName,
                                      String contentType, long sizeBytes, UUID uploadedBy, Instant uploadedAt) {
        super(id);
        this.creditRequestId = creditRequestId;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public UUID getCreditRequestId() { return creditRequestId; }
    public String getStorageKey() { return storageKey; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}

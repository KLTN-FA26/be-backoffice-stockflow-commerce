package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A carrier's waybill document kept with a load (table {@code fulfillment.load_waybill_file}; kltn-docs 09 §4.4, BR-28:
 * optional, "PDF / ảnh / Excel của hãng"; customers never see it). The file is in the {@code documents} category.
 */
@Entity
@Table(name = "load_waybill_file", schema = "fulfillment")
public class LoadWaybillFileJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "storage_key", nullable = false, length = 512, updatable = false) private String storageKey;
    @Column(name = "file_name", nullable = false, length = 255, updatable = false) private String fileName;
    @Column(name = "content_type", nullable = false, length = 100, updatable = false) private String contentType;
    @Column(name = "size_bytes", nullable = false, updatable = false) private long sizeBytes;
    @Column(name = "uploaded_by", nullable = false, updatable = false) private UUID uploadedBy;
    @Column(name = "uploaded_at", nullable = false, updatable = false) private Instant uploadedAt;

    protected LoadWaybillFileJpaEntity() {
    }

    public LoadWaybillFileJpaEntity(UUID id, UUID loadId, String storageKey, String fileName, String contentType,
                                    long sizeBytes, UUID uploadedBy, Instant uploadedAt) {
        super(id);
        this.loadId = loadId;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public UUID getLoadId() { return loadId; }
    public String getStorageKey() { return storageKey; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}

package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A proof of delivery kept with a shipment (table {@code fulfillment.shipment_pod_file}; kltn-docs 09 §4.3 step 2,
 * BR-28, E16: optional, customers never see it). In the {@code documents} category.
 */
@Entity
@Table(name = "shipment_pod_file", schema = "fulfillment")
public class ShipmentPodFileJpaEntity extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "storage_key", nullable = false, length = 512, updatable = false) private String storageKey;
    @Column(name = "file_name", nullable = false, length = 255, updatable = false) private String fileName;
    @Column(name = "content_type", nullable = false, length = 100, updatable = false) private String contentType;
    @Column(name = "size_bytes", nullable = false, updatable = false) private long sizeBytes;
    @Column(name = "uploaded_by", nullable = false, updatable = false) private UUID uploadedBy;
    @Column(name = "uploaded_at", nullable = false, updatable = false) private Instant uploadedAt;

    protected ShipmentPodFileJpaEntity() {
    }

    public ShipmentPodFileJpaEntity(UUID id, UUID shipmentId, String storageKey, String fileName, String contentType,
                                    long sizeBytes, UUID uploadedBy, Instant uploadedAt) {
        super(id);
        this.shipmentId = shipmentId;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public UUID getShipmentId() { return shipmentId; }
    public String getStorageKey() { return storageKey; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}

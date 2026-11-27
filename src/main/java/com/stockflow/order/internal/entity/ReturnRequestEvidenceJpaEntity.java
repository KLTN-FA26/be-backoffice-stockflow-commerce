package com.stockflow.order.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.return_request_evidence}: one photo of a return. Append-only ({@code tg_..._append_only}), so the
 * mapping has no version and every column is insert-only; {@code created_at} is the table's default.
 */
@Entity
@Table(name = "return_request_evidence", schema = "ordering")
public class ReturnRequestEvidenceJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "return_request_id", nullable = false, updatable = false)
    private UUID returnRequestId;

    @Column(name = "stage", nullable = false, length = 16, updatable = false)
    private String stage;

    @Column(name = "file_key", nullable = false, length = 512, updatable = false)
    private String fileKey;

    @Column(name = "original_name", nullable = false, length = 255, updatable = false)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    /** CUSTODY only (WF-05 Q-11): what is kept for the customer; null on every other stage. */
    @Column(name = "custody_sku", length = 64, updatable = false)
    private String custodySku;

    @Column(name = "custody_lpn", length = 40, updatable = false)
    private String custodyLpn;

    @Column(name = "custody_quantity", updatable = false)
    private Integer custodyQuantity;

    @Column(name = "custody_note", length = 1000, updatable = false)
    private String custodyNote;

    protected ReturnRequestEvidenceJpaEntity() {
    }

    public ReturnRequestEvidenceJpaEntity(UUID id, UUID returnRequestId, String stage, String fileKey,
                                          String originalName, String contentType, long sizeBytes, UUID uploadedBy,
                                          Instant uploadedAt) {
        this.id = id;
        this.returnRequestId = returnRequestId;
        this.stage = stage;
        this.fileKey = fileKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() { return id; }
    public UUID getReturnRequestId() { return returnRequestId; }
    public String getStage() { return stage; }
    public String getFileKey() { return fileKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getCustodySku() { return custodySku; }
    public String getCustodyLpn() { return custodyLpn; }
    public Integer getCustodyQuantity() { return custodyQuantity; }
    public String getCustodyNote() { return custodyNote; }

    /** The custody note's facts, on a CUSTODY photo being inserted (WF-05 Q-11). */
    public ReturnRequestEvidenceJpaEntity withCustody(String sku, String lpn, Integer quantity, String note) {
        this.custodySku = sku;
        this.custodyLpn = lpn;
        this.custodyQuantity = quantity;
        this.custodyNote = note;
        return this;
    }
}

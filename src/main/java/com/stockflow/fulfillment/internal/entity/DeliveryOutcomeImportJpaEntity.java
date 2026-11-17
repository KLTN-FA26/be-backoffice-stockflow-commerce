package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One file of delivery outcomes applied (table {@code fulfillment.delivery_outcome_import}; WF-05 fix plan item 5):
 * who applied it and when, its name and digest, and how many lines the check accepted and refused. Written once, before
 * its accepted lines become carrier events; append-only. The file itself is not kept - every line is a row of it.
 */
@Entity
@Table(name = "delivery_outcome_import", schema = "fulfillment")
public class DeliveryOutcomeImportJpaEntity extends BaseEntity {

    @Column(name = "file_name", nullable = false, length = 255, updatable = false) private String fileName;
    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false) private String fileSha256;
    @Column(name = "uploaded_by", nullable = false, updatable = false) private UUID uploadedBy;
    @Column(name = "uploaded_at", nullable = false, updatable = false) private Instant uploadedAt;
    @Column(name = "rows_total", nullable = false, updatable = false) private int rowsTotal;
    @Column(name = "rows_accepted", nullable = false, updatable = false) private int rowsAccepted;
    @Column(name = "rows_rejected", nullable = false, updatable = false) private int rowsRejected;

    protected DeliveryOutcomeImportJpaEntity() {
    }

    public DeliveryOutcomeImportJpaEntity(UUID id, String fileName, String fileSha256, UUID uploadedBy,
                                          Instant uploadedAt, int rowsTotal, int rowsAccepted, int rowsRejected) {
        super(id);
        this.fileName = fileName.length() <= 255 ? fileName : fileName.substring(0, 255);
        this.fileSha256 = fileSha256;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        this.rowsTotal = rowsTotal;
        this.rowsAccepted = rowsAccepted;
        this.rowsRejected = rowsRejected;
    }

    public String getFileName() { return fileName; }
    public String getFileSha256() { return fileSha256; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public int getRowsTotal() { return rowsTotal; }
    public int getRowsAccepted() { return rowsAccepted; }
    public int getRowsRejected() { return rowsRejected; }
}

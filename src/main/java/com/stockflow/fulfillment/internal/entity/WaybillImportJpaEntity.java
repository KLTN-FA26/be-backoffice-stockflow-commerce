package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One file of waybill numbers imported (table {@code fulfillment.waybill_import}; kltn-docs 09 §4.4, BR-25): the file
 * kept in the {@code documents} category, who imported it, and how many lines were applied and refused. Written once,
 * append-only.
 */
@Entity
@Table(name = "waybill_import", schema = "fulfillment")
public class WaybillImportJpaEntity extends BaseEntity {

    @Column(name = "file_name", nullable = false, length = 255, updatable = false) private String fileName;
    @Column(name = "storage_key", nullable = false, length = 512, updatable = false) private String storageKey;
    @Column(name = "uploaded_by", nullable = false, updatable = false) private UUID uploadedBy;
    @Column(name = "uploaded_at", nullable = false, updatable = false) private Instant uploadedAt;
    @Column(name = "rows_total", nullable = false, updatable = false) private int rowsTotal;
    @Column(name = "rows_applied", nullable = false, updatable = false) private int rowsApplied;
    @Column(name = "rows_rejected", nullable = false, updatable = false) private int rowsRejected;

    protected WaybillImportJpaEntity() {
    }

    public WaybillImportJpaEntity(UUID id, String fileName, String storageKey, UUID uploadedBy, Instant uploadedAt,
                                  int rowsTotal, int rowsApplied, int rowsRejected) {
        super(id);
        this.fileName = fileName.length() <= 255 ? fileName : fileName.substring(0, 255);
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        this.rowsTotal = rowsTotal;
        this.rowsApplied = rowsApplied;
        this.rowsRejected = rowsRejected;
    }

    public String getFileName() { return fileName; }
    public String getStorageKey() { return storageKey; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public int getRowsTotal() { return rowsTotal; }
    public int getRowsApplied() { return rowsApplied; }
    public int getRowsRejected() { return rowsRejected; }
}

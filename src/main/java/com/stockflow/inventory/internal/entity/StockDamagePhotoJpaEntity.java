package com.stockflow.inventory.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * {@code inventory.stock_damage_photo}: one photo of a damage report, a child of the report. Append-only in the table
 * ({@code tg_stock_damage_photo_append_only}), so every column is insert-only here: Hibernate never has a reason to
 * write an UPDATE the trigger would refuse.
 */
@Entity
@Table(name = "stock_damage_photo", schema = "inventory")
public class StockDamagePhotoJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "damage_report_id", nullable = false, updatable = false)
    private StockDamageReportJpaEntity report;

    @Column(name = "storage_key", nullable = false, length = 255, updatable = false)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 255, updatable = false)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "checksum", nullable = false, length = 64, updatable = false)
    private String checksum;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    protected StockDamagePhotoJpaEntity() {
    }

    public StockDamagePhotoJpaEntity(UUID id, String storageKey, String originalName, String contentType,
                                     long sizeBytes, String checksum, int sortOrder) {
        this.id = id;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.sortOrder = sortOrder;
    }

    void attachTo(StockDamageReportJpaEntity parent) {
        this.report = parent;
    }

    public UUID getId() { return id; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksum() { return checksum; }
    public int getSortOrder() { return sortOrder; }
}

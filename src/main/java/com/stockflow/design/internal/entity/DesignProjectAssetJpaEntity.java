package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * {@code design.design_project_asset}: a file in a design project's library (kltn-docs 12 §4.9 step 29, BR-18),
 * checked as a design's images are and measured upright. Placing it on a design copies the file into the design; this
 * row is deleted when the file leaves the library, and the file is swept with it.
 */
@Entity
@Table(name = "design_project_asset", schema = "design")
public class DesignProjectAssetJpaEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "storage_key", nullable = false, updatable = false, length = 500)
    private String storageKey;
    @Column(name = "original_name", nullable = false, updatable = false, length = 255)
    private String originalName;
    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;
    @Column(name = "checksum", nullable = false, updatable = false, length = 64)
    private String checksum;
    @Column(name = "width_px", nullable = false, updatable = false)
    private int widthPx;
    @Column(name = "height_px", nullable = false, updatable = false)
    private int heightPx;

    protected DesignProjectAssetJpaEntity() {
    }

    public DesignProjectAssetJpaEntity(UUID projectId, String storageKey, String originalName, String contentType,
                                       long sizeBytes, String checksum, int widthPx, int heightPx) {
        super(Identifiers.newId());
        this.projectId = projectId;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
    }

    public UUID getProjectId() { return projectId; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksum() { return checksum; }
    public int getWidthPx() { return widthPx; }
    public int getHeightPx() { return heightPx; }
}

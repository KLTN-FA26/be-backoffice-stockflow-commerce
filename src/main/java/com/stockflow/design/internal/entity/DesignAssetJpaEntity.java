package com.stockflow.design.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * {@code design.design_asset}: an image a customer uploaded into a design (kltn-docs 12 BR-04), with its size in pixels
 * as the server measured it, upright. Keyed by the design and the image, so a revision holds the same image under the
 * same id as its source (§4.7). Append-only in the table ({@code tg_design_asset_append_only}): every column is
 * insert-only here, so Hibernate never writes an UPDATE the trigger would refuse.
 *
 * <p>{@link Persistable} with an explicit new flag: with an assigned key Spring Data would otherwise merge - a SELECT
 * before every INSERT.</p>
 */
@Entity
@Table(name = "design_asset", schema = "design")
@IdClass(DesignAssetJpaEntity.Key.class)
public class DesignAssetJpaEntity implements Persistable<DesignAssetJpaEntity.Key> {

    /** The table's key: the design and the image. */
    public static class Key implements Serializable {
        private UUID draftId;
        private UUID id;

        protected Key() {
        }

        public Key(UUID draftId, UUID id) {
            this.draftId = draftId;
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(draftId, key.draftId) && Objects.equals(id, key.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(draftId, id);
        }
    }

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

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

    @Column(name = "upload_key", updatable = false, length = 300)
    private String uploadKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false, length = 100)
    private String createdBy;

    @Transient
    private boolean fresh;

    protected DesignAssetJpaEntity() {
    }

    public DesignAssetJpaEntity(UUID draftId, UUID id, String storageKey, String originalName, String contentType,
                                long sizeBytes, String checksum, int widthPx, int heightPx, String uploadKey,
                                Instant createdAt, String createdBy) {
        this.draftId = draftId;
        this.id = id;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.uploadKey = uploadKey;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.fresh = true;
    }

    @PostLoad
    @PostPersist
    void stored() {
        fresh = false;
    }

    @Override
    public Key getId() { return new Key(draftId, id); }

    @Override
    public boolean isNew() { return fresh; }

    public UUID getDraftId() { return draftId; }
    public UUID getAssetId() { return id; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksum() { return checksum; }
    public int getWidthPx() { return widthPx; }
    public int getHeightPx() { return heightPx; }
    public String getUploadKey() { return uploadKey; }
    public Instant getCreatedAt() { return createdAt; }
}

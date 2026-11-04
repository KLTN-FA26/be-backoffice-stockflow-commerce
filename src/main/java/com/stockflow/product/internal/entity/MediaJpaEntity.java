package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.product.internal.domain.ImageRendition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of one image of a variant (table {@code product.media}, decision D5: images belong to
 * a variant, not to the product).
 *
 * <p>An uploaded image keeps its original private and carries one object per display size in
 * {@code renditions}. It reaches the storefront only once {@code is_published}, which a second person
 * sets (four-eyes; {@code ProductPublication} filters out a row published by its own uploader). The
 * row is either an external URL (data carried over from before uploads existed) or a stored file,
 * never both — {@code ck_media_source}.</p>
 */
@Entity
@Table(name = "media", schema = "product")
public class MediaJpaEntity extends BaseEntity {

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(name = "url", columnDefinition = "text", updatable = false)
    private String url;

    @Column(name = "storage_key", length = 500, updatable = false)
    private String storageKey;

    @Column(name = "original_name", length = 255, updatable = false)
    private String originalName;

    @Column(name = "content_type", length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", updatable = false)
    private Long sizeBytes;

    @Column(name = "checksum", length = 64, updatable = false)
    private String checksum;

    @Column(name = "stored_at", updatable = false)
    private Instant storedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "renditions", nullable = false, columnDefinition = "jsonb", updatable = false)
    private List<ImageRendition> renditions = List.of();

    @Column(name = "upload_key", length = 300, updatable = false)
    private String uploadKey;

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    protected MediaJpaEntity() {
    }

    /** An uploaded image: the private original and its display renditions. */
    public MediaJpaEntity(UUID id, UUID variantId, StoredFile file, List<ImageRendition> renditions,
                          String uploadKey, String checksum, int sortOrder) {
        super(id);
        this.variantId = variantId;
        this.kind = "IMAGE";
        this.storageKey = file.key();
        this.originalName = file.originalName();
        this.contentType = file.contentType();
        this.sizeBytes = file.sizeBytes();
        this.storedAt = file.storedAt();
        this.renditions = List.copyOf(renditions);
        this.uploadKey = uploadKey;
        this.checksum = checksum;
        this.sortOrder = sortOrder;
    }

    public void describe(String altText, int sortOrder) {
        this.altText = altText;
        this.sortOrder = sortOrder;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public void publish(UUID by, Instant at) {
        this.published = true;
        this.publishedBy = by;
        this.publishedAt = at;
    }

    /** Off the storefront; who published it last and when stay on the row as history. */
    public void withdraw() {
        this.published = false;
    }

    public StoredFile storedFile() {
        return storageKey == null ? null : new StoredFile(storageKey, originalName, contentType, sizeBytes, storedAt);
    }

    public UUID getVariantId() { return variantId; }
    public String getKind() { return kind; }
    public String getUrl() { return url; }
    public String getStorageKey() { return storageKey; }
    public String getChecksum() { return checksum; }
    public List<ImageRendition> getRenditions() { return renditions; }
    public String getUploadKey() { return uploadKey; }
    public String getAltText() { return altText; }
    public int getSortOrder() { return sortOrder; }
    public boolean isPrimary() { return primary; }
    public boolean isPublished() { return published; }
    public Instant getPublishedAt() { return publishedAt; }
    public UUID getPublishedBy() { return publishedBy; }
}

package com.stockflow.product.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;
import java.time.Instant;
import com.stockflow.common.storage.StoredFile;

/**
 * JPA mapping of one product-media-gallery row. A child of {@code product} — never loaded or
 * saved on its own.
 *
 * <p>{@code @ManyToOne} back to the parent rather than {@link VariantJpaEntity}'s plain
 * {@code productId} column: images are the one child the {@code Product} aggregate actually
 * cascades (whole-list replace on every {@code updateDetails()}), which needs a real bidirectional
 * relationship to drive {@code cascade = ALL}/{@code orphanRemoval}. {@code variant}/{@code sku}/
 * {@code print_config} aren't cascade-managed by any aggregate yet (WBS 3.1.2), so a plain column
 * is enough for them today.</p>
 */
@Entity
@Table(name = "product_image", schema = "product",
        indexes = @Index(name = "ix_product_image_product", columnList = "product_id"))
public class ProductImageJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_product_image_product"))
    private ProductJpaEntity product;

    @Column(name = "url", length = 500)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "storage_key", length = 500)
    private String storageKey;
    @Column(name = "original_name", length = 255)
    private String originalName;
    @Column(name = "content_type", length = 100)
    private String contentType;
    @Column(name = "size_bytes")
    private Long sizeBytes;
    @Column(name = "stored_at")
    private Instant storedAt;

    protected ProductImageJpaEntity() {
    }

    public ProductImageJpaEntity(UUID id, String url, int sortOrder) {
        this.id = id;
        this.url = url;
        this.sortOrder = sortOrder;
    }

    public ProductImageJpaEntity(UUID id, String url, int sortOrder, StoredFile file) {
        this(id, url, sortOrder);
        if (file != null) {
            storageKey = file.key();
            originalName = file.originalName();
            contentType = file.contentType();
            sizeBytes = file.sizeBytes();
            storedAt = file.storedAt();
        }
    }

    public StoredFile storedFile() {
        return storageKey == null ? null
                : new StoredFile(storageKey, originalName, contentType, sizeBytes, storedAt);
    }

    public void attachTo(ProductJpaEntity parent) {
        this.product = parent;
    }

    public UUID getId() { return id; }
    public String getUrl() { return url; }
    public int getSortOrder() { return sortOrder; }
}

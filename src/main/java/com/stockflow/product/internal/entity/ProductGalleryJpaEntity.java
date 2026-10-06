package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.product.internal.domain.GalleryItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;
import java.util.UUID;

/** Product id is also the gallery id. Product row locking serializes creation and edits. */
@Entity
@Table(name = "product_gallery", schema = "product")
public class ProductGalleryJpaEntity extends BaseEntity {
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_items", nullable = false, columnDefinition = "jsonb")
    private List<GalleryItem> workingItems = List.of();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "published_items", nullable = false, columnDefinition = "jsonb")
    private List<GalleryItem> publishedItems = List.of();
    @Column(name = "edited_by")
    private UUID editedBy;
    @Column(name = "approved_by")
    private UUID approvedBy;
    @Column(name = "revision", nullable = false)
    private long revision;
    protected ProductGalleryJpaEntity() { }
    public ProductGalleryJpaEntity(UUID productId) { super(productId); }
    public List<GalleryItem> getWorkingItems() { return List.copyOf(workingItems); }
    public List<GalleryItem> getPublishedItems() { return List.copyOf(publishedItems); }
    public UUID getEditedBy() { return editedBy; }
    public UUID getApprovedBy() { return approvedBy; }
    public long getRevision() { return revision; }
    public void edit(List<GalleryItem> items, UUID actor) {
        workingItems = List.copyOf(items); editedBy = actor; revision++;
    }
    public void publish(UUID actor) {
        publishedItems = List.copyOf(workingItems); approvedBy = actor; revision++;
    }
}

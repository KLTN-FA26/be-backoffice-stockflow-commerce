package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a product category (table {@code product.categories}): a node of the category tree.
 *
 * <p>{@code path} ({@code /ROOT/CHILD}) and {@code depth} are materialised so a subtree is one
 * prefix scan; trigger {@code tg_categories_tree} refuses a row whose path and depth disagree with
 * its parent. They, the parent and the code are therefore fixed at creation: moving a node would
 * rewrite the path of its whole subtree, which no screen asks for yet.</p>
 */
@Entity
@Table(name = "categories", schema = "product")
public class CategoryJpaEntity extends BaseEntity {

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 255, updatable = false)
    private String slug;

    @Column(name = "path", nullable = false, columnDefinition = "text", updatable = false)
    private String path;

    @Column(name = "depth", nullable = false, updatable = false)
    private int depth;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Column(name = "seo_title", length = 255)
    private String seoTitle;

    @Column(name = "seo_description", columnDefinition = "text")
    private String seoDescription;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected CategoryJpaEntity() {
    }

    public CategoryJpaEntity(UUID id, CategoryJpaEntity parent, String code, String slug) {
        super(id);
        this.parentId = parent == null ? null : parent.getId();
        this.code = code;
        this.slug = slug;
        this.path = (parent == null ? "" : parent.getPath()) + "/" + code;
        this.depth = parent == null ? 0 : parent.getDepth() + 1;
        this.active = true;
    }

    public void setDetails(String name, int sortOrder, String imageUrl, String seoTitle, String seoDescription,
                           boolean active) {
        this.name = name;
        this.sortOrder = sortOrder;
        this.imageUrl = imageUrl;
        this.seoTitle = seoTitle;
        this.seoDescription = seoDescription;
        this.active = active;
    }

    public UUID getParentId() { return parentId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getPath() { return path; }
    public int getDepth() { return depth; }
    public int getSortOrder() { return sortOrder; }
    public String getImageUrl() { return imageUrl; }
    public String getSeoTitle() { return seoTitle; }
    public String getSeoDescription() { return seoDescription; }
    public boolean isActive() { return active; }
}

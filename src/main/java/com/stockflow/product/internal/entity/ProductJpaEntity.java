package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a product master row (table {@code product.products}). Not the domain model.
 *
 * <p>Three kinds of column, on purpose:</p>
 * <ul>
 *   <li>the master data the {@code Product} aggregate owns, read and written here;</li>
 *   <li>{@code slug}: written once, at creation, from the code. After that it is selling content,
 *       edited through the catalog by {@code ProductCommerceRepository} (and immutable once the
 *       product was ever published, trigger {@code tg_products_commerce_slug}) — so it is
 *       {@code updatable = false} here, or an admin edit of the name would write back a stale slug;</li>
 *   <li>{@code published_at}, {@code categoryId}, {@code brandName}: read-only. Publication is the
 *       catalog's decision; the primary category lives in {@code product.product_categories} and is
 *       written by the repository adapter; the brand name is read for display. The two
 *       {@code @Formula}s are scalar subqueries, so the paginated list reads them without a join
 *       fetch or an N+1.</li>
 * </ul>
 *
 * <p>SEO fields and {@code ever_published} are not mapped at all: nothing in this module reads them,
 * and an unmapped column is one Hibernate can never overwrite.</p>
 */
@Entity
@Table(name = "products", schema = "product")
public class ProductJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "name_en", length = 300)
    private String nameEn;

    @Column(name = "slug", nullable = false, length = 255, updatable = false)
    private String slug;

    @Column(name = "brand_id")
    private UUID brandId;

    @Formula("(select b.name from product.brands b where b.id = brand_id)")
    private String brandName;

    @Formula("(select pc.category_id from product.product_categories pc where pc.product_id = id and pc.is_primary)")
    private UUID categoryId;

    @Column(name = "short_description", columnDefinition = "text")
    private String shortDescription;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "description_en", columnDefinition = "text")
    private String descriptionEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_class", length = 32)
    private TaxClass taxClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ProductKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProductStatus status;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "published_at", insertable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "discontinued_at")
    private Instant discontinuedAt;

    protected ProductJpaEntity() {
    }

    public ProductJpaEntity(UUID id, String code, String slug) {
        super(id);
        this.code = code;
        this.slug = slug;
    }

    public void setDetails(String name, String nameEn, UUID brandId, String shortDescription, String description,
                           String descriptionEn, TaxClass taxClass, ProductKind kind) {
        this.name = name;
        this.nameEn = nameEn;
        this.brandId = brandId;
        this.shortDescription = shortDescription;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.taxClass = taxClass;
        this.kind = kind;
    }

    public void setWorkflow(ProductStatus status, UUID submittedBy, Instant submittedAt, UUID approvedBy,
                            Instant approvedAt, String rejectionReason, Instant discontinuedAt) {
        this.status = status;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.rejectionReason = rejectionReason;
        this.discontinuedAt = discontinuedAt;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getNameEn() { return nameEn; }
    public String getSlug() { return slug; }
    public UUID getBrandId() { return brandId; }
    public String getBrandName() { return brandName; }
    public UUID getCategoryId() { return categoryId; }
    public String getShortDescription() { return shortDescription; }
    public String getDescription() { return description; }
    public String getDescriptionEn() { return descriptionEn; }
    public TaxClass getTaxClass() { return taxClass; }
    public ProductKind getKind() { return kind; }
    public ProductStatus getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getDiscontinuedAt() { return discontinuedAt; }
}

package com.stockflow.product.internal.entity;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of a product master row (table {@code product.product}). Not the domain model.
 *
 * <p>{@code categoryId} is a same-schema reference to {@code product.category}. Variants and print
 * config live in their own tables ({@link VariantJpaEntity}, {@link PrintConfigJpaEntity}); whether
 * they belong inside the {@code Product} aggregate is still a TODO for when the write use cases
 * exist (WBS 3.1.2).</p>
 */
@Entity
@Table(name = "product", schema = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_code", columnNames = "code"))
public class ProductJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "name_en", length = 300)
    private String nameEn;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "description_en", length = 2000)
    private String descriptionEn;

    @Column(name = "brand", length = 150)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_class", length = 32)
    private TaxClass taxClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProductStatus status;

    /** True when the product can carry a custom print / 3D design (cups, packaging). */
    @Column(name = "customizable", nullable = false)
    private boolean customizable;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "weight_kg", precision = 10, scale = 3)
    private BigDecimal weightKg;

    @Column(name = "length_cm", precision = 10, scale = 2)
    private BigDecimal lengthCm;

    @Column(name = "width_cm", precision = 10, scale = 2)
    private BigDecimal widthCm;

    @Column(name = "height_cm", precision = 10, scale = 2)
    private BigDecimal heightCm;

    /**
     * The media gallery is part of the aggregate, so it is loaded and saved with it —
     * {@code cascade = ALL}, {@code orphanRemoval = true}, and no repository of their own. Mirrors
     * {@code StockItemJpaEntity.reservations}.
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<ProductImageJpaEntity> images = new ArrayList<>();

    /** Forces optimistic version checks even when only the inverse child collection changes. */
    @Column(name = "media_revision", nullable = false)
    private long mediaRevision;

    protected ProductJpaEntity() {
    }

    public ProductJpaEntity(UUID id, String code, String name, String nameEn, UUID categoryId,
                            String description, String descriptionEn, String brand,
                            TaxClass taxClass, ProductStatus status, boolean customizable,
                            UUID submittedBy, Instant submittedAt, UUID approvedBy,
                            Instant approvedAt, String rejectionReason, BigDecimal weightKg,
                            BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {
        super(id);
        this.code = code;
        this.name = name;
        this.nameEn = nameEn;
        this.categoryId = categoryId;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.brand = brand;
        this.taxClass = taxClass;
        this.status = status;
        this.customizable = customizable;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.rejectionReason = rejectionReason;
        this.weightKg = weightKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
    }

    /** Copies every editable field onto a managed row, so Hibernate's dirty checking writes the UPDATE. */
    public void apply(String name, String nameEn, UUID categoryId, String description,
                      String descriptionEn, String brand, TaxClass taxClass, boolean customizable,
                      ProductStatus status, UUID submittedBy, Instant submittedAt, UUID approvedBy,
                      Instant approvedAt, String rejectionReason, BigDecimal weightKg,
                      BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {
        this.name = name;
        this.nameEn = nameEn;
        this.categoryId = categoryId;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.brand = brand;
        this.taxClass = taxClass;
        this.customizable = customizable;
        this.status = status;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.rejectionReason = rejectionReason;
        this.weightKg = weightKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
    }

    public void replaceImages(List<ProductImageJpaEntity> replacement) {
        // Keep already managed children. Recreating the same id after clear() conflicts with
        // Hibernate's persistence context when a second image is appended to the aggregate.
        var retained = replacement.stream().map(ProductImageJpaEntity::getId).toList();
        this.images.removeIf(image -> !retained.contains(image.getId()));
        replacement.forEach(child -> {
            if (this.images.stream().noneMatch(image -> image.getId().equals(child.getId()))) {
                child.attachTo(this);
                this.images.add(child);
            }
        });
        mediaRevision++;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getNameEn() { return nameEn; }
    public UUID getCategoryId() { return categoryId; }
    public String getDescription() { return description; }
    public String getDescriptionEn() { return descriptionEn; }
    public String getBrand() { return brand; }
    public TaxClass getTaxClass() { return taxClass; }
    public ProductStatus getStatus() { return status; }
    public boolean isCustomizable() { return customizable; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public BigDecimal getWeightKg() { return weightKg; }
    public BigDecimal getLengthCm() { return lengthCm; }
    public BigDecimal getWidthCm() { return widthCm; }
    public BigDecimal getHeightCm() { return heightCm; }
    public List<ProductImageJpaEntity> getImages() { return images; }
}

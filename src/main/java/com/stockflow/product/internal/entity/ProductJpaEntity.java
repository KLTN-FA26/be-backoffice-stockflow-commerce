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

    /**
     * The media gallery is part of the aggregate, so it is loaded and saved with it —
     * {@code cascade = ALL}, {@code orphanRemoval = true}, and no repository of their own. Mirrors
     * {@code StockItemJpaEntity.reservations}.
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<ProductImageJpaEntity> images = new ArrayList<>();

    protected ProductJpaEntity() {
    }

    public ProductJpaEntity(UUID id, String code, String name, String nameEn, UUID categoryId,
                            String description, String descriptionEn, String brand,
                            TaxClass taxClass, ProductStatus status, boolean customizable) {
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
    }

    /** Copies every editable field onto a managed row, so Hibernate's dirty checking writes the UPDATE. */
    public void apply(String name, String nameEn, UUID categoryId, String description,
                      String descriptionEn, String brand, TaxClass taxClass, boolean customizable) {
        this.name = name;
        this.nameEn = nameEn;
        this.categoryId = categoryId;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.brand = brand;
        this.taxClass = taxClass;
        this.customizable = customizable;
    }

    public void replaceImages(List<ProductImageJpaEntity> replacement) {
        this.images.clear();
        replacement.forEach(child -> {
            child.attachTo(this);
            this.images.add(child);
        });
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
    public List<ProductImageJpaEntity> getImages() { return images; }
}

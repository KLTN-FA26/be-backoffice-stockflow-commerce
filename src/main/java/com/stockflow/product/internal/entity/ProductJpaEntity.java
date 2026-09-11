package com.stockflow.product.internal.entity;

import com.stockflow.product.internal.domain.ProductStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a product master row (table {@code product.product}). Not the domain model.
 *
 * <p>STARTER ENTITY. {@code categoryId} is a same-schema reference to {@code product.category}.
 * Variants and print config live in their own tables ({@link VariantJpaEntity},
 * {@link PrintConfigJpaEntity}); whether they belong inside the Product aggregate is a TODO for
 * when the write use cases exist.</p>
 */
@Entity
@Table(name = "product", schema = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_code", columnNames = "code"))
public class ProductJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProductStatus status;

    /** True when the product can carry a custom print / 3D design (cups, packaging). */
    @Column(name = "customizable", nullable = false)
    private boolean customizable;

    protected ProductJpaEntity() {
    }

    public ProductJpaEntity(UUID id, String code, String name, UUID categoryId, String description,
                            ProductStatus status, boolean customizable) {
        super(id);
        this.code = code;
        this.name = name;
        this.categoryId = categoryId;
        this.description = description;
        this.status = status;
        this.customizable = customizable;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public UUID getCategoryId() { return categoryId; }
    public String getDescription() { return description; }
    public ProductStatus getStatus() { return status; }
    public boolean isCustomizable() { return customizable; }

    // TODO: brand, weight/dimensions, tax class, media gallery — add with their use cases.
}

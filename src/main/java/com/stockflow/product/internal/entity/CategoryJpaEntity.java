package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a product category (table {@code product.category}). Not the domain model.
 *
 * <p>STARTER ENTITY. {@code parentId} is a same-schema self-reference forming the category tree.</p>
 */
@Entity
@Table(name = "category", schema = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_category_code", columnNames = "code"))
public class CategoryJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Same-schema reference to the parent {@code product.category}; null for a root category. */
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "description", length = 1000)
    private String description;

    protected CategoryJpaEntity() {
    }

    public CategoryJpaEntity(UUID id, String code, String name, UUID parentId, String description) {
        super(id);
        this.code = code;
        this.name = name;
        this.parentId = parentId;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public UUID getParentId() { return parentId; }
    public String getDescription() { return description; }
}

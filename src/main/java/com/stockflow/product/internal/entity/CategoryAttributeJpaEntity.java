package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.product.internal.domain.AttributeRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of one attribute in a category's suggested set (table {@code product.category_attributes}).
 * The pair (category, attribute) is the row's identity, so both columns are fixed at insert; only the
 * role, the required flag and the position change. The two ids are plain columns because the set is
 * replaced as a whole by {@code CategoryAttributeService}, never navigated from either side.
 */
@Entity
@Table(name = "category_attributes", schema = "product")
public class CategoryAttributeJpaEntity extends BaseEntity {

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID attributeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_role", nullable = false, length = 16)
    private AttributeRole defaultRole;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CategoryAttributeJpaEntity() {
    }

    public CategoryAttributeJpaEntity(UUID id, UUID categoryId, UUID attributeId) {
        super(id);
        this.categoryId = categoryId;
        this.attributeId = attributeId;
    }

    public void apply(AttributeRole defaultRole, boolean required, int sortOrder) {
        this.defaultRole = defaultRole;
        this.required = required;
        this.sortOrder = sortOrder;
    }

    public UUID getCategoryId() { return categoryId; }
    public UUID getAttributeId() { return attributeId; }
    public AttributeRole getDefaultRole() { return defaultRole; }
    public boolean isRequired() { return required; }
    public int getSortOrder() { return sortOrder; }
}

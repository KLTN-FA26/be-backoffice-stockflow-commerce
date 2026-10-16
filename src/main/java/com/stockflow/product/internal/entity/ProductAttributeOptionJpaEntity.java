package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of one option a product offers on an axis (table {@code product.product_attribute_options}).
 * Written only through {@link ProductAttributeJpaEntity}. Nothing on it is updatable: a different
 * value is a different option, because variants point at the option row.
 */
@Entity
@Table(name = "product_attribute_options", schema = "product")
public class ProductAttributeOptionJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_attribute_id", nullable = false, updatable = false)
    private ProductAttributeJpaEntity productAttribute;

    @Column(name = "attribute_value_id", nullable = false, updatable = false)
    private UUID attributeValueId;

    protected ProductAttributeOptionJpaEntity() {
    }

    public ProductAttributeOptionJpaEntity(UUID id, UUID attributeValueId) {
        super(id);
        this.attributeValueId = attributeValueId;
    }

    void attachTo(ProductAttributeJpaEntity productAttribute) {
        this.productAttribute = productAttribute;
    }

    public UUID getAttributeValueId() { return attributeValueId; }
}

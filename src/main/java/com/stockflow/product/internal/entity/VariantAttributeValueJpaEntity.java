package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of the option a variant takes on one axis (table {@code product.variant_attribute_values}).
 * Plain ids rather than associations: a variant's options are read for a whole page in one query and
 * resolved against the product's axes in memory, so no navigation is wanted. Nothing is updatable;
 * changing an option means deleting the row and inserting another, and the trigger
 * {@code tg_variant_attribute_values_check} keeps each variant to one option per axis of its own product.
 */
@Entity
@Table(name = "variant_attribute_values", schema = "product")
public class VariantAttributeValueJpaEntity extends BaseEntity {

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "product_attribute_option_id", nullable = false, updatable = false)
    private UUID productAttributeOptionId;

    protected VariantAttributeValueJpaEntity() {
    }

    public VariantAttributeValueJpaEntity(UUID id, UUID variantId, UUID productAttributeOptionId) {
        super(id);
        this.variantId = variantId;
        this.productAttributeOptionId = productAttributeOptionId;
    }

    public UUID getVariantId() { return variantId; }
    public UUID getProductAttributeOptionId() { return productAttributeOptionId; }
}

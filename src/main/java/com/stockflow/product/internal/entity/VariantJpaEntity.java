package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a product variant (table {@code product.variant}). Not the domain model.
 *
 * <p>STARTER ENTITY. A variant is one purchasable form of a product (a size, a colour). It is
 * identified downstream by its {@link SkuJpaEntity}. {@code productId} is a same-schema reference.</p>
 */
@Entity
@Table(name = "variant", schema = "product")
public class VariantJpaEntity extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected VariantJpaEntity() {
    }

    public VariantJpaEntity(UUID id, UUID productId, String name) {
        super(id);
        this.productId = productId;
        this.name = name;
    }

    public UUID getProductId() { return productId; }
    public String getName() { return name; }

    // TODO: attribute values (colour, size, material) — model as columns or a child table once the
    //       attribute schema is decided. See docs/business-design.
}

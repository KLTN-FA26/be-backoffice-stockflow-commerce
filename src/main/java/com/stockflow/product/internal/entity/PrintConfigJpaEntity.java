package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a product's print / 3D configuration (table {@code product.print_config}).
 *
 * <p>STARTER ENTITY. Describes how a customizable product may be printed — the input the design
 * studio reads. {@code spec} is JSON held as text for now. Same-schema reference to
 * {@code product.product}.</p>
 */
@Entity
@Table(name = "print_config", schema = "product")
public class PrintConfigJpaEntity extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "spec", length = 4000)
    private String spec;

    @Column(name = "model_3d_url", length = 500)
    private String model3dUrl;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected PrintConfigJpaEntity() {
    }

    public PrintConfigJpaEntity(UUID id, UUID productId, String spec, String model3dUrl, boolean active) {
        super(id);
        this.productId = productId;
        this.spec = spec;
        this.model3dUrl = model3dUrl;
        this.active = active;
    }

    public UUID getProductId() { return productId; }
    public String getSpec() { return spec; }
    public String getModel3dUrl() { return model3dUrl; }
    public boolean isActive() { return active; }

    // TODO: replace free-text spec with a typed structure once the print schema is finalised.
}

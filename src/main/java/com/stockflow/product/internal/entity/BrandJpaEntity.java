package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a brand (table {@code product.brands}). Small enough to need no aggregate: a code,
 * a display name and a logo, with no rule beyond uniqueness, which the constraints state.
 * {@code code} and {@code slug} are fixed at creation.
 */
@Entity
@Table(name = "brands", schema = "product")
public class BrandJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 255, updatable = false)
    private String slug;

    @Column(name = "logo_url", columnDefinition = "text")
    private String logoUrl;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected BrandJpaEntity() {
    }

    public BrandJpaEntity(UUID id, String code, String slug) {
        super(id);
        this.code = code;
        this.slug = slug;
        this.active = true;
    }

    public void setDetails(String name, String logoUrl, boolean active) {
        this.name = name;
        this.logoUrl = logoUrl;
        this.active = active;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getLogoUrl() { return logoUrl; }
    public boolean isActive() { return active; }
}

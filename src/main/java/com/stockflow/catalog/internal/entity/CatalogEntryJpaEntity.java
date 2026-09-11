package com.stockflow.catalog.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a published storefront entry (table {@code catalog.catalog_entry}). STARTER ENTITY.
 *
 * <p>A read replica built from product + inventory events: {@code sku} and {@code atp} are copied,
 * not owned. {@code atp} may be a second stale — acceptable for a product page, never for the
 * reservation decision (which calls inventory synchronously).</p>
 */
@Entity
@Table(name = "catalog_entry", schema = "catalog",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_catalog_entry_sku", columnNames = "sku"),
                @UniqueConstraint(name = "uk_catalog_entry_slug", columnNames = "slug")})
public class CatalogEntryJpaEntity extends BaseEntity {

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "slug", nullable = false, length = 200)
    private String slug;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "price", precision = 18, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "atp")
    private Integer atp;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "seo_title", length = 300)
    private String seoTitle;

    @Column(name = "seo_description", length = 500)
    private String seoDescription;

    protected CatalogEntryJpaEntity() {
    }

    public CatalogEntryJpaEntity(UUID id, String sku, String title, String slug, String description,
                                 BigDecimal price, String currency, Integer atp, boolean published,
                                 String seoTitle, String seoDescription) {
        super(id);
        this.sku = sku;
        this.title = title;
        this.slug = slug;
        this.description = description;
        this.price = price;
        this.currency = currency;
        this.atp = atp;
        this.published = published;
        this.seoTitle = seoTitle;
        this.seoDescription = seoDescription;
    }

    public String getSku() { return sku; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public Integer getAtp() { return atp; }
    public boolean isPublished() { return published; }
    public String getSeoTitle() { return seoTitle; }
    public String getSeoDescription() { return seoDescription; }
}

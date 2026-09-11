package com.stockflow.catalog.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a pricing rule (table {@code catalog.pricing_rule}). Not the domain model. STARTER ENTITY.
 *
 * <p>Optionally targets a {@code sku} and/or customer {@code segmentId} (cross-module references,
 * plain columns). Highest {@code priority} wins when several match.</p>
 */
@Entity
@Table(name = "pricing_rule", schema = "catalog")
public class PricingRuleJpaEntity extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "sku", length = 64)
    private String sku;

    @Column(name = "segment_id")
    private UUID segmentId;

    @Column(name = "price", nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected PricingRuleJpaEntity() {
    }

    public PricingRuleJpaEntity(UUID id, String name, String sku, UUID segmentId, BigDecimal price,
                                String currency, int priority, boolean active) {
        super(id);
        this.name = name;
        this.sku = sku;
        this.segmentId = segmentId;
        this.price = price;
        this.currency = currency;
        this.priority = priority;
        this.active = active;
    }

    public String getName() { return name; }
    public String getSku() { return sku; }
    public UUID getSegmentId() { return segmentId; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public int getPriority() { return priority; }
    public boolean isActive() { return active; }
}

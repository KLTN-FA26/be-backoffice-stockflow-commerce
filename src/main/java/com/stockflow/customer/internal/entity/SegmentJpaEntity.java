package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a customer segment row (table {@code customer.segment}). Not the domain model.
 *
 * <p>STARTER ENTITY. The columns here are the ones a segment cannot be without; anything richer
 * (pricing rules, membership criteria) is a later migration. See {@code docs/adding-a-module.md} §4.3
 * and {@code inventory.internal.entity.StockItemJpaEntity} for the worked example.</p>
 */
@Entity
@Table(name = "segment", schema = "customer",
        uniqueConstraints = @UniqueConstraint(name = "uk_segment_code", columnNames = "code"))
public class SegmentJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    /** Required by JPA. Application code uses the id-taking constructor. */
    protected SegmentJpaEntity() {
    }

    public SegmentJpaEntity(UUID id, String code, String name, String description) {
        super(id);
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }

    // TODO: add pricing-tier / discount fields when the pricing rules are designed.
}

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
 * <p>A customer group (P7): managed from the back office through {@code CustomerSegmentService}; its prices are
 * catalog's {@code pricing_rule} rows keyed by the group.</p>
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

    /** The code is the group's identity and is never rewritten; name and description are. */
    public void apply(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
